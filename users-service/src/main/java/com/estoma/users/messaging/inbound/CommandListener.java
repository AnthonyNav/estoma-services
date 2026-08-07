package com.estoma.users.messaging.inbound;

import com.estoma.users.application.CommandOutcome;
import com.estoma.users.application.PersonCommandDispatcher;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.outbound.RabbitPublishException;
import com.estoma.users.observability.ConsumerMetrics;
import com.estoma.users.observability.ConsumerNames;
import com.estoma.users.observability.LogContext;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The AMQP entry point for durable commands. Not transactional itself —
 * it validates, calls the dispatcher (which opens and closes the one
 * transaction), and then decides ACK / retry-tier / DLQ. Nothing it does
 * after the dispatcher returns can roll back the commit
 * (docs/system-contract.md §7).
 *
 * <p>The original delivery is ACKed only after its retry/DLQ
 * republication is itself confirmed by RabbitMQ (docs/system-contract.md
 * §7, Round 1) — never on a bare {@code send()}. If the republication
 * cannot be confirmed, the original is NACKed with requeue instead of
 * being silently dropped or acknowledged; if even that NACK fails (e.g.
 * the channel is already closed), the exception is allowed to escape so
 * the broker redelivers once the connection recovers.
 */
@Component
public class CommandListener {

  private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final PersonCommandDispatcher dispatcher;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failureRouter;
  private final ConsumerMetrics metrics;

  public CommandListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      PersonCommandDispatcher dispatcher,
      TransientFailureClassifier classifier,
      FailureRouter failureRouter,
      ConsumerMetrics metrics) {
    this.codec = codec;
    this.validator = validator;
    this.dispatcher = dispatcher;
    this.classifier = classifier;
    this.failureRouter = failureRouter;
    this.metrics = metrics;
  }

  @RabbitListener(
      id = "commandListener", // referenced by CommandConsumerHealthIndicator
      queues = AmqpNames.QUEUE_COMMANDS,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    metrics.received();

    // (1) decode + structural validation. Any failure here is PERMANENT.
    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validate(envelope);
    } catch (Exception e) {
      log.error("invalid message on {}: {}", AmqpNames.QUEUE_COMMANDS, e.toString());
      // Counted the moment the message is classified invalid, even if the
      // DLQ republication below cannot be confirmed.
      metrics.invalidMessage();
      try {
        failureRouter.toDlq(message, e);
        metrics.deadLettered(); // only after the DLQ publication is confirmed
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishException) {
        metrics.technicalFailure();
        nackRequeue(channel, deliveryTag);
      }
      return;
    }

    LogContext.putEnvelopeContext(envelope, ConsumerNames.COMMANDS);
    Instant start = Instant.now();
    try {
      // (2) business processing: exactly one local transaction inside dispatch().
      CommandOutcome outcome = dispatcher.dispatch(envelope);
      recordOutcome(outcome);
      log.info("command {} processed: {}", envelope.messageType(), describe(outcome));
      // (3) commit already happened; result + event rows are durable.
      channel.basicAck(deliveryTag, false); // ◀ the ACK. After commit. Nothing else here.
    } catch (Exception e) {
      boolean transientFailure = classifier.isTransient(e);
      log.warn(
          "command {} (messageId={}) failed, transient={}: {}",
          envelope.messageType(),
          envelope.messageId(),
          transientFailure,
          e.toString());
      try {
        FailureRoute route = failureRouter.routeAfterFailure(message, e, transientFailure);
        if (route == FailureRoute.RETRY) {
          metrics.technicalFailure();
          metrics.retried(); // only after the retry-tier publication is confirmed
        } else {
          metrics.deadLettered(); // only after the DLQ publication is confirmed
        }
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishException) {
        metrics.technicalFailure();
        nackRequeue(channel, deliveryTag);
      }
    } finally {
      metrics.recordProcessingTime(Duration.between(start, Instant.now()));
      LogContext.clear();
    }
  }

  /**
   * The republication that would have justified acknowledging the
   * original delivery could not be confirmed — conserve the original by
   * NACKing it with requeue instead of ACKing or silently dropping it.
   * If the channel is already closed and even this fails, the exception
   * is allowed to escape uncaught: no ACK is attempted, and RabbitMQ
   * redelivers once the connection recovers. No sleep, no local blocking
   * retry.
   *
   * <p>Known limitation of this round: if retry/DLQ stays unroutable, the
   * requeue can produce a tight redelivery loop. Conserving the message
   * is prioritized over avoiding that loop; it is observable via {@code
   * technical_failures} and the {@code PUBLISH_*} logs
   * (docs/system-contract.md §7).
   */
  private static void nackRequeue(Channel channel, long deliveryTag) throws IOException {
    channel.basicNack(deliveryTag, false, true);
  }

  private void recordOutcome(CommandOutcome outcome) {
    switch (outcome) {
      case CommandOutcome.Succeeded succeeded -> metrics.succeeded();
      case CommandOutcome.Rejected rejected -> {
        LogContext.putErrorCode(rejected.code().name());
        metrics.rejected();
      }
      case CommandOutcome.Duplicate duplicate -> metrics.duplicate();
    }
  }

  private static String describe(CommandOutcome outcome) {
    return switch (outcome) {
      case CommandOutcome.Succeeded succeeded -> "succeeded, aggregateVersion=" + succeeded.aggregateVersion();
      case CommandOutcome.Rejected rejected -> "rejected, code=" + rejected.code();
      case CommandOutcome.Duplicate duplicate -> "duplicate";
    };
  }
}

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** AMQP entry point for durable Person commands. */
@Component
@ConditionalOnProperty(
    prefix = "estoma.messaging",
    name = "listeners-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CommandListener {

  private static final Logger log = LoggerFactory.getLogger(CommandListener.class);
  private static final String TERMINAL_TECHNICAL_ERROR = "TECHNICAL_FAILURE";

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
      id = "commandListener",
      queues = AmqpNames.QUEUE_COMMANDS,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    metrics.received();

    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validate(envelope);
    } catch (Exception e) {
      log.error("invalid message on {}: {}", AmqpNames.QUEUE_COMMANDS, e.toString());
      metrics.invalidMessage();
      try {
        failureRouter.toDlq(message, e);
        metrics.deadLettered();
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
      CommandOutcome outcome = dispatcher.dispatch(envelope);
      recordOutcome(outcome);
      log.info("command {} processed: {}", envelope.messageType(), describe(outcome));
      channel.basicAck(deliveryTag, false);
    } catch (Exception e) {
      boolean transientFailure = classifier.isTransient(e);
      log.warn(
          "command {} (messageId={}) failed, transient={}: {}",
          envelope.messageType(), envelope.messageId(), transientFailure, e.toString());

      // Coordinated Academic Commands have a canonical terminal Result contract.
      // Once retry is impossible, persist FAILED through the Transactional Outbox
      // instead of stranding the Process Manager behind a DLQ-only outcome.
      if ("academic".equals(envelope.producer())
          && !failureRouter.commandRetryAvailable(message, transientFailure)) {
        terminalizeAcademicFailure(message, channel, deliveryTag, envelope, e);
        return;
      }

      try {
        FailureRoute route = failureRouter.routeAfterFailure(message, e, transientFailure);
        if (route == FailureRoute.RETRY) {
          metrics.technicalFailure();
          metrics.retried();
        } else {
          metrics.deadLettered();
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

  private void terminalizeAcademicFailure(
      Message original,
      Channel channel,
      long deliveryTag,
      MessageEnvelope envelope,
      Exception originalFailure) throws IOException {
    try {
      dispatcher.fail(envelope, TERMINAL_TECHNICAL_ERROR);
      metrics.technicalFailure();
      channel.basicAck(deliveryTag, false);
    } catch (Exception terminalPersistenceFailure) {
      log.error(
          "could not persist terminal FAILED Result for command {} (messageId={}): {}",
          envelope.messageType(), envelope.messageId(), terminalPersistenceFailure.toString());
      try {
        failureRouter.toDlq(original, terminalPersistenceFailure);
        metrics.deadLettered();
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishException) {
        metrics.technicalFailure();
        nackRequeue(channel, deliveryTag);
      }
    }
  }

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

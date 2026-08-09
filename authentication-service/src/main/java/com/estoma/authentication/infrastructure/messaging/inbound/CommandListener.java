package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.application.CommandDispatcher;
import com.estoma.authentication.application.CommandOutcome;
import com.estoma.authentication.application.AccountAuthorizationPolicy;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessagingNames;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.infrastructure.messaging.outbound.RabbitPublishException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The AMQP entry point for durable commands. Not transactional itself --
 * it validates, calls {@link CommandDispatcher} (which opens and closes
 * the one transaction, including Consumer Inbox dedup), and then decides
 * ACK / retry-tier / DLQ. Nothing here can roll back a commit that
 * already happened.
 *
 * <p>The original delivery is ACKed only after its retry/DLQ
 * republication is itself confirmed by RabbitMQ -- never on a bare
 * {@code send()}. If the republication cannot be confirmed, the original
 * is NACKed with requeue instead of silently dropped or acknowledged.
 */
@Component
public class CommandListener {

  private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final AccountAuthorizationPolicy authorizationPolicy;
  private final CommandDispatcher dispatcher;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failureRouter;

  public CommandListener(
      EnvelopeJsonCodec codec, EnvelopeValidator validator, AccountAuthorizationPolicy authorizationPolicy, CommandDispatcher dispatcher, TransientFailureClassifier classifier, FailureRouter failureRouter) {
    this.codec = codec;
    this.validator = validator;
    this.authorizationPolicy = authorizationPolicy;
    this.dispatcher = dispatcher;
    this.classifier = classifier;
    this.failureRouter = failureRouter;
  }

  @RabbitListener(
      id = "commandListener", // referenced by CommandConsumerHealthIndicator
      queues = MessagingNames.AUTHENTICATION_COMMAND_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();

    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validateCommand(envelope);
      authorizationPolicy.check(envelope);
    } catch (Exception e) {
      log.error("invalid message on {}: {}", MessagingNames.AUTHENTICATION_COMMAND_QUEUE, e.toString());
      try {
        failureRouter.toDlq(message, FailureRouter.COMMAND_TOPOLOGY, e);
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishException) {
        nackRequeue(channel, deliveryTag);
      }
      return;
    }

    try {
      CommandOutcome outcome = dispatcher.dispatch(envelope);
      log.info("command {} processed: {}", envelope.messageType(), describe(outcome));
      channel.basicAck(deliveryTag, false); // after commit -- nothing else here
    } catch (Exception e) {
      boolean transientFailure = classifier.isTransient(e);
      log.warn("command {} (messageId={}) failed, transient={}: {}", envelope.messageType(), envelope.messageId(), transientFailure, e.toString());
      try {
        failureRouter.routeAfterFailure(message, FailureRouter.COMMAND_TOPOLOGY, e, transientFailure);
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishException) {
        nackRequeue(channel, deliveryTag);
      }
    }
  }

  private static void nackRequeue(Channel channel, long deliveryTag) throws IOException {
    channel.basicNack(deliveryTag, false, true);
  }

  private static String describe(CommandOutcome outcome) {
    return switch (outcome) {
      case CommandOutcome.Succeeded succeeded -> "succeeded, aggregateVersion=" + succeeded.aggregateVersion();
      case CommandOutcome.Rejected rejected -> "rejected, code=" + rejected.code();
      case CommandOutcome.Duplicate duplicate -> "duplicate";
    };
  }
}

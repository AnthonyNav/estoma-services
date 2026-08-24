package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.application.CommandDispatcher;
import com.estoma.authentication.application.CommandOutcome;
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

@Component
public class CommandListener {

  private static final Logger log = LoggerFactory.getLogger(CommandListener.class);
  private static final String TERMINAL_TECHNICAL_ERROR = "TECHNICAL_FAILURE";

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final CommandDispatcher dispatcher;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failureRouter;

  public CommandListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      CommandDispatcher dispatcher,
      TransientFailureClassifier classifier,
      FailureRouter failureRouter) {
    this.codec = codec;
    this.validator = validator;
    this.dispatcher = dispatcher;
    this.classifier = classifier;
    this.failureRouter = failureRouter;
  }

  @RabbitListener(
      id = "commandListener",
      queues = MessagingNames.AUTHENTICATION_COMMAND_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validateCommand(envelope);
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
      channel.basicAck(deliveryTag, false);
    } catch (Exception e) {
      boolean transientFailure = classifier.isTransient(e);
      log.warn("command {} (messageId={}) failed, transient={}: {}",
          envelope.messageType(), envelope.messageId(), transientFailure, e.toString());

      if ("academic".equals(envelope.producer())
          && !failureRouter.retryAvailable(message, FailureRouter.COMMAND_TOPOLOGY, transientFailure)) {
        terminalizeAcademicFailure(message, channel, deliveryTag, envelope, e);
        return;
      }

      try {
        failureRouter.routeAfterFailure(message, FailureRouter.COMMAND_TOPOLOGY, e, transientFailure);
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishException) {
        nackRequeue(channel, deliveryTag);
      }
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
      channel.basicAck(deliveryTag, false);
    } catch (Exception terminalPersistenceFailure) {
      log.error("could not persist terminal FAILED Result for {} (messageId={}): {}",
          envelope.messageType(), envelope.messageId(), terminalPersistenceFailure.toString());
      try {
        failureRouter.toDlq(original, FailureRouter.COMMAND_TOPOLOGY, terminalPersistenceFailure);
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
      case CommandOutcome.Deferred deferred -> "deferred, pendingReason=" + deferred.pendingReason();
      case CommandOutcome.Duplicate duplicate -> "duplicate";
    };
  }
}

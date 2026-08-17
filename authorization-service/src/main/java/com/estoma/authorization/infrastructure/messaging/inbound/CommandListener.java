package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.application.command.CommandOutcome;
import com.estoma.authorization.application.port.CommandDispatcher;
import com.estoma.authorization.application.port.TerminalCommandFailureRecorder;
import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.MessagingNames;
import com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authorization.infrastructure.messaging.MessagingLog;
import com.estoma.authorization.infrastructure.messaging.MessagingMetrics;
import com.estoma.authorization.infrastructure.messaging.outbound.RabbitPublishException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
final class CommandListener {

  private static final String CONSUMER = "commands";
  private static final Set<String> TYPES = Set.of(
      "AssignAccountRole",
      "ChangeAccountRole",
      "GrantSystemAccess",
      "SuspendSystemAccess",
      "RevokeSystemAccess",
      "RestoreSystemAccess");

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final CommandMessageMapper mapper;
  private final CommandDispatcher dispatcher;
  private final TerminalCommandFailureRecorder terminalFailures;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;
  private final MessagingMetrics metrics;
  private final MessagingLog log;

  CommandListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      CommandMessageMapper mapper,
      CommandDispatcher dispatcher,
      TerminalCommandFailureRecorder terminalFailures,
      TransientFailureClassifier classifier,
      FailureRouter failures,
      MessagingMetrics metrics,
      MessagingLog log) {
    this.codec = codec;
    this.validator = validator;
    this.mapper = mapper;
    this.dispatcher = dispatcher;
    this.terminalFailures = terminalFailures;
    this.classifier = classifier;
    this.failures = failures;
    this.metrics = metrics;
    this.log = log;
  }

  @RabbitListener(id = "commandConsumer", queues = MessagingNames.COMMAND_QUEUE, containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long started = System.nanoTime();
    try {
      handle(message, channel);
    } finally {
      metrics.processingDuration(CONSUMER, System.nanoTime() - started);
    }
  }

  private void handle(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    CommandMessageMapper.MappedCommand mapped;
    try {
      envelope = validator.validate(codec.readTree(message.getBody()), MessageKind.COMMAND, TYPES);
      mapped = mapper.map(envelope);
    } catch (RuntimeException exception) {
      log.transportFailure(null, message, CONSUMER, "INVALID", exception);
      routeInvalid(message, channel, deliveryTag);
      return;
    }

    metrics.consumed(CONSUMER);
    metrics.commandReceived();
    try {
      CommandOutcome outcome = dispatcher.dispatch(mapped.metadata(), mapped.command());
      metrics.commandOutcome(outcome);
      if (outcome.duplicateDelivery()) {
        metrics.duplicate(CONSUMER);
      }
      String processingResult = outcome.deferred()
          ? "DEFERRED"
          : outcome.result().map(result -> result.status().name())
              .orElse(outcome.duplicateDelivery() ? "DUPLICATE" : "APPLIED");
      String errorCode = outcome.result()
          .filter(result -> result.error() != null)
          .map(result -> result.error().code())
          .orElse(null);
      log.processed(envelope, message, CONSUMER, processingResult, errorCode);
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException failure) {
      log.transportFailure(envelope, message, CONSUMER, "TECHNICAL_FAILURE", failure);
      if (classifier.isTransient(failure) && failures.isFinalAttempt(message, FailureRouter.COMMANDS)) {
        recordTerminalFailureOrDlq(message, channel, deliveryTag, envelope, mapped);
        return;
      }
      routeTechnical(message, channel, deliveryTag, classifier.isTransient(failure));
    }
  }

  private void recordTerminalFailureOrDlq(
      Message message,
      Channel channel,
      long deliveryTag,
      MessageEnvelope envelope,
      CommandMessageMapper.MappedCommand mapped) throws IOException {
    try {
      CommandOutcome outcome = terminalFailures.recordTerminalFailure(mapped.metadata(), mapped.command());
      metrics.commandOutcome(outcome);
      log.processed(envelope, message, CONSUMER, "FAILED", "TECHNICAL_FAILURE");
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException terminalPersistenceFailure) {
      log.transportFailure(
          envelope, message, CONSUMER, "TERMINAL_PERSISTENCE_FAILED", terminalPersistenceFailure);
      routeInvalid(message, channel, deliveryTag);
    }
  }

  private void routeInvalid(Message message, Channel channel, long deliveryTag) throws IOException {
    try {
      failures.toDlq(message, FailureRouter.COMMANDS);
      metrics.deadLettered(CONSUMER);
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException publishFailure) {
      channel.basicNack(deliveryTag, false, true);
    }
  }

  private void routeTechnical(
      Message message, Channel channel, long deliveryTag, boolean transientFailure) throws IOException {
    try {
      FailureRoute route = failures.routeAfterFailure(message, FailureRouter.COMMANDS, transientFailure);
      if (route == FailureRoute.RETRY) {
        metrics.retried(CONSUMER);
      } else {
        metrics.deadLettered(CONSUMER);
      }
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException publishFailure) {
      channel.basicNack(deliveryTag, false, true);
    }
  }
}

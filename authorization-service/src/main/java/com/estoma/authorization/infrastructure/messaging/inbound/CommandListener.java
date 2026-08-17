package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.application.command.CommandOutcome;
import com.estoma.authorization.application.port.CommandDispatcher;
import com.estoma.authorization.application.port.TerminalCommandFailureRecorder;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.MessagingNames;
import com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec;
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

  CommandListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      CommandMessageMapper mapper,
      CommandDispatcher dispatcher,
      TerminalCommandFailureRecorder terminalFailures,
      TransientFailureClassifier classifier,
      FailureRouter failures,
      MessagingMetrics metrics) {
    this.codec = codec;
    this.validator = validator;
    this.mapper = mapper;
    this.dispatcher = dispatcher;
    this.terminalFailures = terminalFailures;
    this.classifier = classifier;
    this.failures = failures;
    this.metrics = metrics;
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
    CommandMessageMapper.MappedCommand mapped;
    try {
      mapped = mapper.map(validator.validate(codec.readTree(message.getBody()), MessageKind.COMMAND, TYPES));
    } catch (RuntimeException exception) {
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
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException failure) {
      if (classifier.isTransient(failure) && failures.isFinalAttempt(message, FailureRouter.COMMANDS)) {
        recordTerminalFailureOrDlq(message, channel, deliveryTag, mapped);
        return;
      }
      routeTechnical(message, channel, deliveryTag, classifier.isTransient(failure));
    }
  }

  private void recordTerminalFailureOrDlq(
      Message message,
      Channel channel,
      long deliveryTag,
      CommandMessageMapper.MappedCommand mapped) throws IOException {
    try {
      CommandOutcome outcome = terminalFailures.recordTerminalFailure(mapped.metadata(), mapped.command());
      metrics.commandOutcome(outcome);
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException terminalPersistenceFailure) {
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

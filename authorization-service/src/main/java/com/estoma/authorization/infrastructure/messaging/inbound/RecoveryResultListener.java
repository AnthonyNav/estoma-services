package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.application.recovery.ProjectionRecoveryResultHandler;
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
final class RecoveryResultListener {

  private static final String CONSUMER = "recoveryResults";
  private static final Set<String> TYPES = Set.of(
      "GetPersonStatusSnapshotResult",
      "ListPersonStatusSnapshotsPageResult",
      "GetAccountStatusSnapshotResult",
      "ListAccountStatusSnapshotsPageResult",
      "GetStudentAcademicEligibilitySnapshotResult",
      "ListStudentAcademicEligibilitySnapshotsPageResult");

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final RecoveryResultMessageMapper mapper;
  private final ProjectionRecoveryResultHandler handler;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;
  private final MessagingMetrics metrics;

  RecoveryResultListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      RecoveryResultMessageMapper mapper,
      ProjectionRecoveryResultHandler handler,
      TransientFailureClassifier classifier,
      FailureRouter failures,
      MessagingMetrics metrics) {
    this.codec = codec;
    this.validator = validator;
    this.mapper = mapper;
    this.handler = handler;
    this.classifier = classifier;
    this.failures = failures;
    this.metrics = metrics;
  }

  @RabbitListener(id = "recoveryResultConsumer", queues = MessagingNames.RESULT_QUEUE, containerFactory = "manualAckContainerFactory")
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
    try {
      var result = mapper.map(validator.validate(codec.readTree(message.getBody()), MessageKind.RESULT, TYPES));
      var handling = handler.handle(result);
      metrics.consumed(CONSUMER);
      if (handling.status() == ProjectionRecoveryResultHandler.Status.DUPLICATE) {
        metrics.duplicate(CONSUMER);
      } else if (handling.status() == ProjectionRecoveryResultHandler.Status.STALE_RESULT) {
        metrics.staleRecoveryResult();
      }
      channel.basicAck(deliveryTag, false);
    } catch (InvalidMessageException exception) {
      routeInvalid(message, channel, deliveryTag);
    } catch (RuntimeException exception) {
      routeTechnical(message, channel, deliveryTag, classifier.isTransient(exception));
    }
  }

  private void routeInvalid(Message message, Channel channel, long deliveryTag) throws IOException {
    try {
      failures.toDlq(message, FailureRouter.RESULTS);
      metrics.deadLettered(CONSUMER);
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException publishFailure) {
      channel.basicNack(deliveryTag, false, true);
    }
  }

  private void routeTechnical(
      Message message, Channel channel, long deliveryTag, boolean transientFailure) throws IOException {
    try {
      FailureRoute route = failures.routeAfterFailure(message, FailureRouter.RESULTS, transientFailure);
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

package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.application.projection.ProjectionEventProcessor;
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
final class ExternalEventListener {

  private static final String CONSUMER = "events";
  private static final Set<String> TYPES = Set.of(
      "PersonCreated",
      "PersonActivated",
      "PersonDeactivated",
      "AccountCreated",
      "AccountActivated",
      "AccountDeactivated",
      "StudentAcademicEligibilityChanged");

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final ExternalEventMessageMapper mapper;
  private final ProjectionEventProcessor processor;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;
  private final MessagingMetrics metrics;
  private final MessagingLog log;

  ExternalEventListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      ExternalEventMessageMapper mapper,
      ProjectionEventProcessor processor,
      TransientFailureClassifier classifier,
      FailureRouter failures,
      MessagingMetrics metrics,
      MessagingLog log) {
    this.codec = codec;
    this.validator = validator;
    this.mapper = mapper;
    this.processor = processor;
    this.classifier = classifier;
    this.failures = failures;
    this.metrics = metrics;
    this.log = log;
  }

  @RabbitListener(id = "externalEventConsumer", queues = MessagingNames.EVENT_QUEUE, containerFactory = "manualAckContainerFactory")
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
    try {
      envelope = validator.validate(codec.readTree(message.getBody()), MessageKind.EVENT, TYPES);
      var event = mapper.map(envelope);
      var result = processor.process(event);
      metrics.consumed(CONSUMER);
      if (result.status() == ProjectionEventProcessor.Status.DEFERRED) {
        metrics.deferred();
      }
      log.processed(envelope, message, CONSUMER, result.status().name(), null);
      channel.basicAck(deliveryTag, false);
    } catch (InvalidMessageException exception) {
      log.transportFailure(null, message, CONSUMER, "INVALID", exception);
      routeInvalid(message, channel, deliveryTag);
    } catch (RuntimeException exception) {
      log.transportFailure(null, message, CONSUMER, "TECHNICAL_FAILURE", exception);
      routeTechnical(message, channel, deliveryTag, classifier.isTransient(exception));
    }
  }

  private void routeInvalid(Message message, Channel channel, long deliveryTag) throws IOException {
    try {
      failures.toDlq(message, FailureRouter.EVENTS);
      metrics.deadLettered(CONSUMER);
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException publishFailure) {
      channel.basicNack(deliveryTag, false, true);
    }
  }

  private void routeTechnical(
      Message message, Channel channel, long deliveryTag, boolean transientFailure) throws IOException {
    try {
      FailureRoute route = failures.routeAfterFailure(message, FailureRouter.EVENTS, transientFailure);
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

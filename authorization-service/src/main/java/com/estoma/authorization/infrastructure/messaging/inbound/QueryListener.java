package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.application.query.AuthorizationSnapshotQueryResponder;
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
final class QueryListener {

  private static final String CONSUMER = "queries";
  private static final Set<String> TYPES = Set.of(
      "GetAccountAuthorizationSnapshot", "ListAccountAuthorizationSnapshotsPage");

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final QueryMessageMapper mapper;
  private final AuthorizationSnapshotQueryResponder responder;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;
  private final MessagingMetrics metrics;

  QueryListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      QueryMessageMapper mapper,
      AuthorizationSnapshotQueryResponder responder,
      TransientFailureClassifier classifier,
      FailureRouter failures,
      MessagingMetrics metrics) {
    this.codec = codec;
    this.validator = validator;
    this.mapper = mapper;
    this.responder = responder;
    this.classifier = classifier;
    this.failures = failures;
    this.metrics = metrics;
  }

  @RabbitListener(id = "queryConsumer", queues = MessagingNames.QUERY_QUEUE, containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    QueryMessageMapper.MappedQuery mapped;
    try {
      mapped = mapper.map(
          validator.validate(codec.readTree(message.getBody()), MessageKind.QUERY, TYPES));
    } catch (InvalidMessageException exception) {
      routeInvalid(message, channel, deliveryTag);
      return;
    } catch (RuntimeException exception) {
      routeTechnical(message, channel, deliveryTag, classifier.isTransient(exception));
      return;
    }

    try {
      responder.respond(mapped.metadata(), mapped.query());
      metrics.consumed(CONSUMER);
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException exception) {
      boolean transientFailure = classifier.isTransient(exception);
      if (!transientFailure || failures.isFinalAttempt(message, FailureRouter.QUERIES)) {
        recordTerminalFailureOrDlq(message, channel, deliveryTag, mapped);
        return;
      }
      routeTechnical(message, channel, deliveryTag, true);
    }
  }

  private void recordTerminalFailureOrDlq(
      Message message,
      Channel channel,
      long deliveryTag,
      QueryMessageMapper.MappedQuery mapped) throws IOException {
    try {
      responder.respondFailed(mapped.metadata(), "TECHNICAL_FAILURE");
      metrics.consumed(CONSUMER);
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException terminalPersistenceFailure) {
      routeInvalid(message, channel, deliveryTag);
    }
  }

  private void routeInvalid(Message message, Channel channel, long deliveryTag) throws IOException {
    try {
      failures.toDlq(message, FailureRouter.QUERIES);
      metrics.deadLettered(CONSUMER);
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException publishFailure) {
      channel.basicNack(deliveryTag, false, true);
    }
  }

  private void routeTechnical(
      Message message, Channel channel, long deliveryTag, boolean transientFailure) throws IOException {
    try {
      FailureRoute route = failures.routeAfterFailure(message, FailureRouter.QUERIES, transientFailure);
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

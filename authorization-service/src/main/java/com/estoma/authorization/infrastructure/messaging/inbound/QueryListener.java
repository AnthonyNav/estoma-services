package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.application.query.AuthorizationSnapshotQueryResponder;
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
final class QueryListener {

  private static final String CONSUMER = "queries";
  private static final Set<String> TYPES = Set.of(
      "GetAccountAuthorizationSnapshot",
      "ListAccountAuthorizationSnapshotsPage",
      "GetSystemCatalogSnapshot",
      "ListSystemCatalogSnapshotsPage");

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final QueryMessageMapper mapper;
  private final AuthorizationSnapshotQueryResponder responder;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;
  private final MessagingMetrics metrics;
  private final MessagingLog log;

  QueryListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      QueryMessageMapper mapper,
      AuthorizationSnapshotQueryResponder responder,
      TransientFailureClassifier classifier,
      FailureRouter failures,
      MessagingMetrics metrics,
      MessagingLog log) {
    this.codec = codec;
    this.validator = validator;
    this.mapper = mapper;
    this.responder = responder;
    this.classifier = classifier;
    this.failures = failures;
    this.metrics = metrics;
    this.log = log;
  }

  @RabbitListener(id = "queryConsumer", queues = MessagingNames.QUERY_QUEUE, containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long started = System.nanoTime();
    try {
      handle(message, channel, started);
    } finally {
      metrics.processingDuration(CONSUMER, System.nanoTime() - started);
    }
  }

  private void handle(Message message, Channel channel, long started) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    QueryMessageMapper.MappedQuery mapped;
    try {
      envelope = validator.validate(codec.readTree(message.getBody()), MessageKind.QUERY, TYPES);
      mapped = mapper.map(envelope);
    } catch (InvalidMessageException exception) {
      log.transportFailure(null, message, CONSUMER, "INVALID", exception, System.nanoTime() - started, null);
      routeInvalid(message, channel, deliveryTag);
      return;
    } catch (RuntimeException exception) {
      log.transportFailure(null, message, CONSUMER, "TECHNICAL_FAILURE", exception, System.nanoTime() - started, null);
      routeTechnical(message, channel, deliveryTag, classifier.isTransient(exception));
      return;
    }

    try {
      responder.respond(mapped.metadata(), mapped.query());
      metrics.consumed(CONSUMER);
      log.processed(envelope, message, CONSUMER, "RESULT_DURABLE", null, System.nanoTime() - started, null);
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException exception) {
      log.transportFailure(envelope, message, CONSUMER, "TECHNICAL_FAILURE", exception, System.nanoTime() - started, null);
      boolean transientFailure = classifier.isTransient(exception);
      if (!transientFailure || failures.isFinalAttempt(message, FailureRouter.QUERIES)) {
        recordTerminalFailureOrDlq(message, channel, deliveryTag, envelope, mapped, started);
        return;
      }
      routeTechnical(message, channel, deliveryTag, true);
    }
  }

  private void recordTerminalFailureOrDlq(
      Message message,
      Channel channel,
      long deliveryTag,
      MessageEnvelope envelope,
      QueryMessageMapper.MappedQuery mapped,
      long started) throws IOException {
    try {
      responder.respondFailed(mapped.metadata(), "TECHNICAL_FAILURE");
      metrics.consumed(CONSUMER);
      log.processed(envelope, message, CONSUMER, "FAILED", "TECHNICAL_FAILURE", System.nanoTime() - started, null);
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException terminalPersistenceFailure) {
      log.transportFailure(
          envelope, message, CONSUMER, "TERMINAL_PERSISTENCE_FAILED", terminalPersistenceFailure,
          System.nanoTime() - started, null);
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

  private void routeTechnical(Message message, Channel channel, long deliveryTag, boolean transientFailure)
      throws IOException {
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

package com.estoma.users.messaging.inbound;

import com.estoma.users.application.PersonQueryDispatcher;
import com.estoma.users.config.MessagingProperties;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.outbound.RabbitPublishException;
import com.estoma.users.observability.ConsumerNames;
import com.estoma.users.observability.LogContext;
import com.estoma.users.observability.QueryConsumerMetrics;
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

@Component
@ConditionalOnProperty(
    prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
public class QueryListener {

  private static final Logger log = LoggerFactory.getLogger(QueryListener.class);
  private static final int PHYSICAL_RETRY_TIERS = 3;

  private final EnvelopeJsonCodec codec;
  private final QueryEnvelopeValidator validator;
  private final PersonQueryDispatcher dispatcher;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failureRouter;
  private final QueryConsumerMetrics metrics;
  private final MessagingProperties properties;

  public QueryListener(
      EnvelopeJsonCodec codec,
      QueryEnvelopeValidator validator,
      PersonQueryDispatcher dispatcher,
      TransientFailureClassifier classifier,
      FailureRouter failureRouter,
      QueryConsumerMetrics metrics,
      MessagingProperties properties) {
    this.codec = codec;
    this.validator = validator;
    this.dispatcher = dispatcher;
    this.classifier = classifier;
    this.failureRouter = failureRouter;
    this.metrics = metrics;
    this.properties = properties;
  }

  @RabbitListener(
      id = "queryListener",
      queues = AmqpNames.QUEUE_QUERIES,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    metrics.received();

    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validate(envelope);
    } catch (Exception invalid) {
      metrics.invalidMessage();
      try {
        failureRouter.toDlq(message, invalid, AmqpNames.ROUTING_KEY_QUERIES_DLQ);
        metrics.deadLettered();
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishFailure) {
        metrics.technicalFailure();
        channel.basicNack(deliveryTag, false, true);
      }
      return;
    }

    LogContext.putEnvelopeContext(envelope, ConsumerNames.QUERIES);
    Instant start = Instant.now();
    try {
      dispatcher.resolve(envelope);
      metrics.resolved();
      channel.basicAck(deliveryTag, false);
    } catch (Exception failure) {
      boolean transientFailure = classifier.isTransient(failure);
      if (!transientFailure || isFinalAttempt(message)) {
        terminalizeOrDlq(envelope, message, channel, deliveryTag, failure);
      } else {
        retryOrRequeue(message, channel, deliveryTag, failure);
      }
    } finally {
      metrics.recordProcessingTime(Duration.between(start, Instant.now()));
      LogContext.clear();
    }
  }

  private void terminalizeOrDlq(
      MessageEnvelope envelope,
      Message message,
      Channel channel,
      long deliveryTag,
      Exception originalFailure) throws IOException {
    try {
      dispatcher.fail(envelope, "TECHNICAL_FAILURE");
      metrics.technicalFailure();
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException terminalPersistenceFailure) {
      log.error("failed to persist terminal Query Result after {}: {}",
          originalFailure.toString(), terminalPersistenceFailure.toString());
      try {
        failureRouter.toDlq(message, terminalPersistenceFailure, AmqpNames.ROUTING_KEY_QUERIES_DLQ);
        metrics.deadLettered();
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishFailure) {
        channel.basicNack(deliveryTag, false, true);
      }
    }
  }

  private void retryOrRequeue(
      Message message, Channel channel, long deliveryTag, Exception failure) throws IOException {
    try {
      failureRouter.routeAfterFailure(
          message, failure, true, FailureRouter.RetryTopology.QUERIES, properties.queryMaxAttempts());
      metrics.technicalFailure();
      metrics.retried();
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException publishFailure) {
      channel.basicNack(deliveryTag, false, true);
    }
  }

  private boolean isFinalAttempt(Message message) {
    int effectiveMax = Math.min(properties.queryMaxAttempts(), PHYSICAL_RETRY_TIERS);
    Object header = message.getMessageProperties().getHeaders().get(AmqpNames.HEADER_ATTEMPT);
    int attempt = header instanceof Number number ? number.intValue() : 0;
    return attempt >= effectiveMax;
  }
}

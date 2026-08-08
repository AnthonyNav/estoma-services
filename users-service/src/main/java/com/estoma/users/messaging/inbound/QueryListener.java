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

/**
 * The AMQP entry point for {@code ResolvePersonByInstitutionalEmail}
 * (queries.md §1) — structurally {@link CommandListener}'s twin, on
 * {@code queries.users-service} instead of {@code commands.users-service},
 * using {@link FailureRouter.RetryTopology#QUERIES} for its own DLQ/retry
 * tiers. The two differ where the contract itself differs: {@link
 * PersonQueryDispatcher#resolve} returns {@code void} (a Query has no
 * {@code CommandOutcome} — every well-formed request produces a
 * successful resolution, so there is no outcome to branch metrics/logging
 * on), and there is no Consumer Inbox context to seed.
 */
@Component
@ConditionalOnProperty(
    prefix = "estoma.messaging",
    name = "listeners-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class QueryListener {

  private static final Logger log = LoggerFactory.getLogger(QueryListener.class);

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
      id = "queryListener", // referenced by health/observability, mirroring commandListener
      queues = AmqpNames.QUEUE_QUERIES,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    metrics.received();

    // (1) decode + structural validation. Any failure here is PERMANENT.
    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validate(envelope);
    } catch (Exception e) {
      log.error("invalid message on {}: {}", AmqpNames.QUEUE_QUERIES, e.toString());
      metrics.invalidMessage();
      try {
        failureRouter.toDlq(message, e, AmqpNames.ROUTING_KEY_QUERIES_DLQ);
        metrics.deadLettered();
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishException) {
        metrics.technicalFailure();
        nackRequeue(channel, deliveryTag);
      }
      return;
    }

    LogContext.putEnvelopeContext(envelope, ConsumerNames.QUERIES);
    Instant start = Instant.now();
    try {
      // (2) side-effect-free resolution: one SELECT, one Outbox INSERT,
      // no Consumer Inbox, no domain mutation, no Event.
      dispatcher.resolve(envelope);
      metrics.resolved();
      log.info("query {} resolved", envelope.messageType());
      channel.basicAck(deliveryTag, false); // ◀ the ACK. After the Outbox row is durable.
    } catch (Exception e) {
      boolean transientFailure = classifier.isTransient(e);
      log.warn(
          "query {} (messageId={}) failed, transient={}: {}",
          envelope.messageType(),
          envelope.messageId(),
          transientFailure,
          e.toString());
      try {
        FailureRoute route =
            failureRouter.routeAfterFailure(
                message, e, transientFailure, FailureRouter.RetryTopology.QUERIES, properties.queryMaxAttempts());
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

  /** Mirrors {@code CommandListener#nackRequeue} — see its Javadoc for the reasoning. */
  private static void nackRequeue(Channel channel, long deliveryTag) throws IOException {
    channel.basicNack(deliveryTag, false, true);
  }
}

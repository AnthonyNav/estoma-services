package com.estoma.users.messaging.inbound;

import com.estoma.users.config.MessagingProperties;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.messaging.outbound.ConfirmedRabbitPublisher;
import com.estoma.users.messaging.outbound.RabbitPublishException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

/** Routes failed durable deliveries through the configured retry tiers or DLQ. */
@Component
public class FailureRouter {

  /** Physical retry tiers actually provisioned by {@code AmqpTopologyConfig}. */
  private static final int TIER_COUNT = 3;

  private static final Set<String> ALLOWED_HEADERS = Set.of("traceparent", "tracestate", "baggage");
  private static final Logger log = LoggerFactory.getLogger(FailureRouter.class);

  private final ConfirmedRabbitPublisher publisher;
  private final MessagingProperties properties;

  public FailureRouter(ConfirmedRabbitPublisher publisher, MessagingProperties properties) {
    this.publisher = publisher;
    this.properties = properties;
  }

  /** The routing keys one durable queue's retry/DLQ topology hangs off {@code platform.dlx}. */
  public record RetryTopology(
      String retryRoutingKey5s, String retryRoutingKey30s, String retryRoutingKey300s, String dlqRoutingKey) {

    public static final RetryTopology COMMANDS =
        new RetryTopology(
            AmqpNames.ROUTING_KEY_RETRY_5S,
            AmqpNames.ROUTING_KEY_RETRY_30S,
            AmqpNames.ROUTING_KEY_RETRY_300S,
            AmqpNames.ROUTING_KEY_DLQ);

    public static final RetryTopology QUERIES =
        new RetryTopology(
            AmqpNames.ROUTING_KEY_QUERIES_RETRY_5S,
            AmqpNames.ROUTING_KEY_QUERIES_RETRY_30S,
            AmqpNames.ROUTING_KEY_QUERIES_RETRY_300S,
            AmqpNames.ROUTING_KEY_QUERIES_DLQ);

    String retryRoutingKeyFor(int attempt) {
      return switch (attempt) {
        case 1 -> retryRoutingKey5s;
        case 2 -> retryRoutingKey30s;
        case 3 -> retryRoutingKey300s;
        default -> throw new IllegalStateException("no retry tier configured for attempt " + attempt);
      };
    }
  }

  /** Returns whether a command failure still has a physical retry tier available. */
  public boolean commandRetryAvailable(Message originalMessage, boolean transientFailure) {
    if (!transientFailure) {
      return false;
    }
    int nextAttempt = currentAttempt(originalMessage) + 1;
    return nextAttempt <= Math.min(properties.commandMaxAttempts(), TIER_COUNT);
  }

  public FailureRoute routeAfterFailure(Message originalMessage, Throwable cause, boolean transientFailure) {
    return routeAfterFailure(
        originalMessage, cause, transientFailure, RetryTopology.COMMANDS, properties.commandMaxAttempts());
  }

  public FailureRoute routeAfterFailure(
      Message originalMessage, Throwable cause, boolean transientFailure, RetryTopology topology, int maxAttempts) {
    int nextAttempt = currentAttempt(originalMessage) + 1;
    int effectiveMaxAttempts = Math.min(maxAttempts, TIER_COUNT);

    if (transientFailure && nextAttempt <= effectiveMaxAttempts) {
      toRetryTier(originalMessage, nextAttempt, cause, topology);
      return FailureRoute.RETRY;
    }
    toDlq(originalMessage, cause, topology.dlqRoutingKey());
    return FailureRoute.DLQ;
  }

  void toRetryTier(Message originalMessage, int attempt, Throwable cause) {
    toRetryTier(originalMessage, attempt, cause, RetryTopology.COMMANDS);
  }

  void toRetryTier(Message originalMessage, int attempt, Throwable cause, RetryTopology topology) {
    String routingKey = topology.retryRoutingKeyFor(attempt);
    Message republished = withAttemptHeader(originalMessage, attempt);
    log.warn("routing message to retry tier {} (attempt {}): {}", routingKey, attempt, cause.toString());
    publisher.publishRequiredRoute(AmqpNames.EXCHANGE_DLX, routingKey, republished);
  }

  public void toDlq(Message originalMessage, Throwable cause) {
    toDlq(originalMessage, cause, AmqpNames.ROUTING_KEY_DLQ);
  }

  public void toDlq(Message originalMessage, Throwable cause, String dlqRoutingKey) {
    log.error("routing message straight to DLQ: {}", cause == null ? "invalid message" : cause.toString());
    Message republished = safeCopy(originalMessage, originalMessage.getMessageProperties().getHeaders());
    publisher.publishRequiredRoute(AmqpNames.EXCHANGE_DLX, dlqRoutingKey, republished);
  }

  private static int currentAttempt(Message message) {
    Object header = message.getMessageProperties().getHeaders().get(AmqpNames.HEADER_ATTEMPT);
    return header instanceof Number number ? number.intValue() : 0;
  }

  private static Message withAttemptHeader(Message original, int attempt) {
    Message copy = safeCopy(original, original.getMessageProperties().getHeaders());
    copy.getMessageProperties().setHeader(AmqpNames.HEADER_ATTEMPT, attempt);
    return copy;
  }

  private static Message safeCopy(Message original, java.util.Map<String, Object> originalHeaders) {
    MessageProperties originalProps = original.getMessageProperties();
    MessageProperties copy = new MessageProperties();
    copy.setContentType(originalProps.getContentType());
    copy.setContentEncoding(originalProps.getContentEncoding());
    copy.setMessageId(originalProps.getMessageId());
    copy.setCorrelationId(originalProps.getCorrelationId());
    copy.setDeliveryMode(originalProps.getDeliveryMode());
    for (String allowedHeader : ALLOWED_HEADERS) {
      if (originalHeaders.containsKey(allowedHeader)) {
        copy.setHeader(allowedHeader, originalHeaders.get(allowedHeader));
      }
    }
    if (originalHeaders.containsKey(AmqpNames.HEADER_ATTEMPT)) {
      copy.setHeader(AmqpNames.HEADER_ATTEMPT, originalHeaders.get(AmqpNames.HEADER_ATTEMPT));
    }
    return new Message(original.getBody(), copy);
  }
}

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

/**
 * Routes a failed delivery to the next retry tier or straight to the DLQ,
 * per docs/system-contract.md §7, §9. Never re-delivers in process —
 * always republishes onto {@code platform.dlx} and lets the broker do
 * the waiting, so the consumer thread stays free and the attempt count
 * survives a pod restart.
 *
 * <p>Generalized over a {@link RetryTopology} rather than hardcoded to
 * the commands queue's routing keys, so {@code QueryListener} can reuse
 * the exact same retry/DLQ logic against {@code queries.users-service}'s
 * own tiers — only the routing-key constants differ, not the mechanism.
 * The original commands-only methods are thin delegates to {@link
 * RetryTopology#COMMANDS}, so {@code CommandListener} needs no changes.
 *
 * <p>Both {@link #routeAfterFailure} and {@link #toDlq} only return once
 * {@link ConfirmedRabbitPublisher} has confirmed the republication was
 * acknowledged and routed — never on a bare {@code send()}. If it cannot
 * be confirmed, {@link RabbitPublishException} propagates uncaught; the
 * caller ({@code CommandListener}/{@code QueryListener}) must not ACK the
 * original delivery in that case (docs/system-contract.md §7, Round 1).
 */
@Component
public class FailureRouter {

  /** Physical retry tiers actually provisioned by {@code AmqpTopologyConfig}. */
  private static final int TIER_COUNT = 3;

  /**
   * The only headers copied from the original message onto its retry/DLQ
   * republication, besides the explicitly-set core properties below.
   * Deliberately not "copy everything then strip" — an allow-list, so a
   * header nobody vetted never rides along by accident
   * (docs/system-contract.md §7, Round 1).
   */
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

  /**
   * @param transientFailure whether the failure that triggered this call
   *     was classified retryable ({@link TransientFailureClassifier}). A
   *     permanent failure always goes straight to the DLQ regardless of
   *     attempt count.
   * @return which route the message was confirmed onto
   */
  public FailureRoute routeAfterFailure(Message originalMessage, Throwable cause, boolean transientFailure) {
    return routeAfterFailure(
        originalMessage, cause, transientFailure, RetryTopology.COMMANDS, properties.commandMaxAttempts());
  }

  /** Overload for a non-command queue's own retry topology and max-attempts budget (e.g. queries). */
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

  /**
   * Builds a republication of {@code original} that shares its body but
   * has brand-new {@link MessageProperties} — never mutates {@code
   * original}. Copies a fixed allow-list of core properties and headers;
   * everything RabbitMQ attached to the previous delivery (delivery tag,
   * consumer tag/queue, received exchange/routing key, redelivered flag,
   * x-death and friends) is left behind on purpose.
   */
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
    // Preserve the attempt count already on the message, if any -- callers
    // that need a different value (toRetryTier) overwrite it afterward.
    if (originalHeaders.containsKey(AmqpNames.HEADER_ATTEMPT)) {
      copy.setHeader(AmqpNames.HEADER_ATTEMPT, originalHeaders.get(AmqpNames.HEADER_ATTEMPT));
    }
    return new Message(original.getBody(), copy);
  }
}

package com.estoma.users.messaging.inbound;

import com.estoma.users.config.MessagingProperties;
import com.estoma.users.messaging.AmqpNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Routes a failed command delivery to the next retry tier or straight to
 * the DLQ, per docs/system-contract.md §7, §9. Never re-delivers in
 * process — always republishes onto {@code platform.dlx} and lets the
 * broker do the waiting, so the consumer thread stays free and the
 * attempt count survives a pod restart.
 */
@Component
public class FailureRouter {

  /** Physical retry tiers actually provisioned by {@code AmqpTopologyConfig}. */
  private static final int TIER_COUNT = 3;

  private static final Logger log = LoggerFactory.getLogger(FailureRouter.class);

  private final RabbitTemplate rabbitTemplate;
  private final MessagingProperties properties;

  public FailureRouter(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
    this.rabbitTemplate = rabbitTemplate;
    this.properties = properties;
  }

  /**
   * @param transientFailure whether the failure that triggered this call
   *     was classified retryable ({@link TransientFailureClassifier}). A
   *     permanent failure always goes straight to the DLQ regardless of
   *     attempt count.
   */
  public void routeAfterFailure(Message originalMessage, Throwable cause, boolean transientFailure) {
    int nextAttempt = currentAttempt(originalMessage) + 1;
    int effectiveMaxAttempts = Math.min(properties.commandMaxAttempts(), TIER_COUNT);

    if (transientFailure && nextAttempt <= effectiveMaxAttempts) {
      toRetryTier(originalMessage, nextAttempt, cause);
    } else {
      toDlq(originalMessage, cause);
    }
  }

  void toRetryTier(Message originalMessage, int attempt, Throwable cause) {
    String routingKey = retryRoutingKeyFor(attempt);
    Message republished = withAttemptHeader(originalMessage, attempt);
    log.warn("routing message to retry tier {} (attempt {}): {}", routingKey, attempt, cause.toString());
    rabbitTemplate.send(AmqpNames.EXCHANGE_DLX, routingKey, republished);
  }

  public void toDlq(Message originalMessage, Throwable cause) {
    log.error("routing message straight to DLQ: {}", cause == null ? "invalid message" : cause.toString());
    rabbitTemplate.send(AmqpNames.EXCHANGE_DLX, AmqpNames.ROUTING_KEY_DLQ, originalMessage);
  }

  private static int currentAttempt(Message message) {
    Object header = message.getMessageProperties().getHeaders().get(AmqpNames.HEADER_ATTEMPT);
    return header instanceof Number number ? number.intValue() : 0;
  }

  private static Message withAttemptHeader(Message original, int attempt) {
    MessageProperties properties = original.getMessageProperties();
    properties.getHeaders().put(AmqpNames.HEADER_ATTEMPT, attempt);
    return new Message(original.getBody(), properties);
  }

  private static String retryRoutingKeyFor(int attempt) {
    return switch (attempt) {
      case 1 -> AmqpNames.ROUTING_KEY_RETRY_5S;
      case 2 -> AmqpNames.ROUTING_KEY_RETRY_30S;
      case 3 -> AmqpNames.ROUTING_KEY_RETRY_300S;
      default -> throw new IllegalStateException("no retry tier configured for attempt " + attempt);
    };
  }
}

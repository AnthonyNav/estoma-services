package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.infrastructure.messaging.AmqpNames;
import com.estoma.authentication.infrastructure.messaging.outbound.ConfirmedRabbitPublisher;
import com.estoma.authentication.infrastructure.messaging.outbound.RabbitPublishException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

/**
 * Routes a failed command or event delivery to the next retry tier or
 * straight to the DLQ. Never re-delivers in process -- always republishes
 * onto {@code platform.dlx} and lets the broker do the waiting, so the
 * consumer thread stays free and the attempt count survives a pod
 * restart.
 *
 * <p>Both {@link #routeAfterFailure} and {@link #toDlq} only return once
 * {@link ConfirmedRabbitPublisher} has confirmed the republication; if it
 * cannot be confirmed, {@link RabbitPublishException} propagates
 * uncaught and the caller must not ACK the original delivery.
 */
@Component
public class FailureRouter {

  /** Retry tiers for {@code commands.authentication-service}: 5s, 30s, 300s, all dead-lettering back onto the same routing key. */
  public static final RetryTopology COMMAND_TOPOLOGY =
      new RetryTopology(
          List.of(AmqpNames.ROUTING_KEY_COMMANDS_RETRY_5S, AmqpNames.ROUTING_KEY_COMMANDS_RETRY_30S, AmqpNames.ROUTING_KEY_COMMANDS_RETRY_300S),
          AmqpNames.ROUTING_KEY_COMMANDS_DLQ,
          3);

  /** A single 5s retry tier for {@code events.authentication-service}. */
  public static final RetryTopology EVENTS_TOPOLOGY =
      new RetryTopology(List.of(AmqpNames.ROUTING_KEY_EVENTS_RETRY_5S), AmqpNames.ROUTING_KEY_EVENTS_DLQ, 1);

  public record RetryTopology(List<String> retryRoutingKeysByAttempt, String dlqRoutingKey, int maxAttempts) {}

  /** Allow-list of headers copied onto a republication; a header nobody vetted never rides along by accident. */
  private static final Set<String> ALLOWED_HEADERS = Set.of("traceparent", "tracestate", "baggage");

  private static final Logger log = LoggerFactory.getLogger(FailureRouter.class);

  private final ConfirmedRabbitPublisher publisher;

  public FailureRouter(ConfirmedRabbitPublisher publisher) {
    this.publisher = publisher;
  }

  public FailureRoute routeAfterFailure(Message originalMessage, RetryTopology topology, Throwable cause, boolean transientFailure) {
    int nextAttempt = currentAttempt(originalMessage) + 1;
    if (transientFailure && nextAttempt <= topology.maxAttempts()) {
      toRetryTier(originalMessage, topology, nextAttempt, cause);
      return FailureRoute.RETRY;
    }
    toDlq(originalMessage, topology, cause);
    return FailureRoute.DLQ;
  }

  private void toRetryTier(Message originalMessage, RetryTopology topology, int attempt, Throwable cause) {
    String routingKey = topology.retryRoutingKeysByAttempt().get(attempt - 1);
    Message republished = withAttemptHeader(originalMessage, attempt);
    log.warn("routing message to retry tier {} (attempt {}): {}", routingKey, attempt, cause.toString());
    publisher.publishRequiredRoute(AmqpNames.EXCHANGE_DLX, routingKey, republished);
  }

  public void toDlq(Message originalMessage, RetryTopology topology, Throwable cause) {
    log.error("routing message straight to DLQ: {}", cause == null ? "invalid message" : cause.toString());
    Message republished = safeCopy(originalMessage, originalMessage.getMessageProperties().getHeaders());
    publisher.publishRequiredRoute(AmqpNames.EXCHANGE_DLX, topology.dlqRoutingKey(), republished);
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

  /** Builds a republication sharing the body but with brand-new {@link MessageProperties} -- never mutates {@code original}. */
  private static Message safeCopy(Message original, Map<String, Object> originalHeaders) {
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

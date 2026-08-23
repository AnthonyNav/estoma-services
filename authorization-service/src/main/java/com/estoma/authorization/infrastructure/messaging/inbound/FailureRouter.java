package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.contracts.MessagingNames;
import com.estoma.authorization.infrastructure.messaging.AmqpNames;
import com.estoma.authorization.infrastructure.messaging.outbound.ConfirmedRabbitPublisher;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

/** Republishes to a durable retry tier or DLQ before the original delivery is ACKed. */
@Component
final class FailureRouter {

  static final RetryTopology COMMANDS = new RetryTopology(
      List.of(MessagingNames.COMMAND_RETRY_1, MessagingNames.COMMAND_RETRY_2),
      MessagingNames.COMMAND_DLQ);
  static final RetryTopology QUERIES = new RetryTopology(
      List.of(MessagingNames.QUERY_RETRY_1, MessagingNames.QUERY_RETRY_2),
      MessagingNames.QUERY_DLQ);
  static final RetryTopology EVENTS = new RetryTopology(
      List.of(MessagingNames.EVENT_RETRY_1, MessagingNames.EVENT_RETRY_2),
      MessagingNames.EVENT_DLQ);
  static final RetryTopology RESULTS = new RetryTopology(
      List.of(MessagingNames.RESULT_RETRY_1, MessagingNames.RESULT_RETRY_2),
      MessagingNames.RESULT_DLQ);

  private static final Set<String> ALLOWED_HEADERS = Set.of("traceparent", "tracestate", "baggage");
  private final ConfirmedRabbitPublisher publisher;

  FailureRouter(ConfirmedRabbitPublisher publisher) {
    this.publisher = publisher;
  }

  FailureRoute routeAfterFailure(Message original, RetryTopology topology, boolean transientFailure) {
    int nextAttempt = currentAttempt(original) + 1;
    if (transientFailure && nextAttempt <= topology.maxAttempts()) {
      publisher.publishRequiredRoute(
          MessagingNames.DLX_EXCHANGE,
          topology.retryRoutingKey(nextAttempt),
          withAttemptHeader(original, nextAttempt));
      return FailureRoute.RETRY;
    }
    toDlq(original, topology);
    return FailureRoute.DLQ;
  }

  void toDlq(Message original, RetryTopology topology) {
    publisher.publishRequiredRoute(
        MessagingNames.DLX_EXCHANGE,
        topology.dlqRoutingKey(),
        safeCopy(original, original.getMessageProperties().getHeaders()));
  }

  boolean isFinalAttempt(Message message, RetryTopology topology) {
    return currentAttempt(message) >= topology.maxAttempts();
  }

  private static int currentAttempt(Message message) {
    Object value = message.getMessageProperties().getHeaders().get(AmqpNames.HEADER_ATTEMPT);
    return value instanceof Number number && number.intValue() > 0 ? number.intValue() : 1;
  }

  private static Message withAttemptHeader(Message original, int attempt) {
    Message copy = safeCopy(original, original.getMessageProperties().getHeaders());
    copy.getMessageProperties().setHeader(AmqpNames.HEADER_ATTEMPT, attempt);
    return copy;
  }

  private static Message safeCopy(Message original, Map<String, Object> headers) {
    MessageProperties source = original.getMessageProperties();
    MessageProperties target = new MessageProperties();
    target.setContentType(source.getContentType());
    target.setContentEncoding(source.getContentEncoding());
    target.setMessageId(source.getMessageId());
    target.setCorrelationId(source.getCorrelationId());
    target.setDeliveryMode(source.getDeliveryMode());
    for (String key : ALLOWED_HEADERS) {
      if (headers.containsKey(key)) {
        target.setHeader(key, headers.get(key));
      }
    }
    if (headers.containsKey(AmqpNames.HEADER_ATTEMPT)) {
      target.setHeader(AmqpNames.HEADER_ATTEMPT, headers.get(AmqpNames.HEADER_ATTEMPT));
    }
    return new Message(original.getBody(), target);
  }

  record RetryTopology(List<String> retryRoutingKeys, String dlqRoutingKey) {
    int maxAttempts() {
      return retryRoutingKeys.size() + 1;
    }

    String retryRoutingKey(int deliveryAttempt) {
      if (deliveryAttempt < 2 || deliveryAttempt > maxAttempts()) {
        throw new IllegalArgumentException("no retry tier for delivery attempt " + deliveryAttempt);
      }
      return retryRoutingKeys.get(deliveryAttempt - 2);
    }
  }
}

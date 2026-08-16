package com.estoma.washadministration.infrastructure.messaging.inbound;

import com.estoma.washadministration.contracts.MessagingNames;
import com.estoma.washadministration.infrastructure.messaging.AmqpNames;
import com.estoma.washadministration.infrastructure.messaging.outbound.ConfirmedRabbitPublisher;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

@Component
public class FailureRouter {

  public static final RetryTopology COMMAND_TOPOLOGY =
      new RetryTopology(
          List.of(MessagingNames.COMMAND_RETRY_1, MessagingNames.COMMAND_RETRY_2),
          MessagingNames.COMMAND_DLQ,
          3);
  public static final RetryTopology QUERY_TOPOLOGY =
      new RetryTopology(
          List.of(MessagingNames.QUERY_RETRY_1, MessagingNames.QUERY_RETRY_2),
          MessagingNames.QUERY_DLQ,
          3);
  public static final RetryTopology EVENTS_TOPOLOGY =
      new RetryTopology(
          List.of(MessagingNames.EVENTS_RETRY_1, MessagingNames.EVENTS_RETRY_2),
          MessagingNames.EVENTS_DLQ,
          3);
  public static final RetryTopology RESULTS_TOPOLOGY =
      new RetryTopology(
          List.of(MessagingNames.RESULTS_RETRY_1, MessagingNames.RESULTS_RETRY_2),
          MessagingNames.RESULTS_DLQ,
          3);

  private static final Set<String> ALLOWED_HEADERS = Set.of("traceparent", "tracestate", "baggage");

  private final ConfirmedRabbitPublisher publisher;

  public FailureRouter(ConfirmedRabbitPublisher publisher) {
    this.publisher = publisher;
  }

  public FailureRoute routeAfterFailure(
      Message original, RetryTopology topology, Throwable cause, boolean transientFailure) {
    int nextAttempt = currentAttempt(original) + 1;
    if (transientFailure
        && nextAttempt <= topology.maxAttempts()
        && nextAttempt <= topology.retryRoutingKeysByAttempt().size()) {
      publisher.publishRequiredRoute(
          MessagingNames.DLX_EXCHANGE,
          topology.retryRoutingKeysByAttempt().get(nextAttempt - 1),
          withAttemptHeader(original, nextAttempt));
      return FailureRoute.RETRY;
    }
    toDlq(original, topology);
    return FailureRoute.DLQ;
  }

  public void toDlq(Message original, RetryTopology topology) {
    publisher.publishRequiredRoute(
        MessagingNames.DLX_EXCHANGE,
        topology.dlqRoutingKey(),
        safeCopy(original, original.getMessageProperties().getHeaders()));
  }

  private static int currentAttempt(Message message) {
    Object value = message.getMessageProperties().getHeaders().get(AmqpNames.HEADER_ATTEMPT);
    return value instanceof Number number ? number.intValue() : 0;
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

  public record RetryTopology(List<String> retryRoutingKeysByAttempt, String dlqRoutingKey, int maxAttempts) {}
}

package com.estoma.communications.infrastructure.messaging;

import com.estoma.communications.contracts.MessagingNames;
import java.util.List;
import java.util.Set;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

@Component
public class FailureRouter {

  public record Topology(List<String> retryKeys, String dlqKey) {}

  public static final Topology COMMANDS = new Topology(
      List.of(MessagingNames.COMMAND_RETRY_5S, MessagingNames.COMMAND_RETRY_30S, MessagingNames.COMMAND_RETRY_300S),
      MessagingNames.COMMAND_DLQ);
  public static final Topology EVENTS = new Topology(
      List.of(MessagingNames.EVENT_RETRY_5S, MessagingNames.EVENT_RETRY_30S, MessagingNames.EVENT_RETRY_300S),
      MessagingNames.EVENT_DLQ);

  private static final Set<String> PASSTHROUGH_HEADERS = Set.of("traceparent", "tracestate", "baggage");
  private final ConfirmedRabbitPublisher publisher;

  public FailureRouter(ConfirmedRabbitPublisher publisher) {
    this.publisher = publisher;
  }

  public void route(Message original, Topology topology, Throwable cause, boolean transientFailure) {
    int nextAttempt = currentAttempt(original) + 1;
    if (transientFailure && nextAttempt <= topology.retryKeys().size()) {
      Message retry = copy(original);
      retry.getMessageProperties().setHeader(MessagingNames.HEADER_ATTEMPT, nextAttempt);
      publisher.publishRequiredRoute(MessagingNames.DLX_EXCHANGE, topology.retryKeys().get(nextAttempt - 1), retry);
      return;
    }
    toDlq(original, topology);
  }

  public void toDlq(Message original, Topology topology) {
    publisher.publishRequiredRoute(MessagingNames.DLX_EXCHANGE, topology.dlqKey(), copy(original));
  }

  private static int currentAttempt(Message message) {
    Object value = message.getMessageProperties().getHeaders().get(MessagingNames.HEADER_ATTEMPT);
    return value instanceof Number number ? number.intValue() : 0;
  }

  private static Message copy(Message original) {
    MessageProperties source = original.getMessageProperties();
    MessageProperties target = new MessageProperties();
    target.setContentType(source.getContentType());
    target.setContentEncoding(source.getContentEncoding());
    target.setMessageId(source.getMessageId());
    target.setCorrelationId(source.getCorrelationId());
    target.setDeliveryMode(source.getDeliveryMode());
    for (String header : PASSTHROUGH_HEADERS) {
      if (source.getHeaders().containsKey(header)) target.setHeader(header, source.getHeaders().get(header));
    }
    if (source.getHeaders().containsKey(MessagingNames.HEADER_ATTEMPT)) {
      target.setHeader(MessagingNames.HEADER_ATTEMPT, source.getHeaders().get(MessagingNames.HEADER_ATTEMPT));
    }
    return new Message(original.getBody(), target);
  }
}

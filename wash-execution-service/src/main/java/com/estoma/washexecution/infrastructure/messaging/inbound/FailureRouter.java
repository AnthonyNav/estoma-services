package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.contracts.MessagingNames;
import com.estoma.washexecution.infrastructure.messaging.AmqpNames;
import com.estoma.washexecution.infrastructure.messaging.outbound.ConfirmedRabbitPublisher;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

@Component
public final class FailureRouter {
  public record Topology(String retry1, String retry2, String dlq) {}

  public static final Topology COMMAND = new Topology(
      MessagingNames.COMMAND_RETRY_1, MessagingNames.COMMAND_RETRY_2, MessagingNames.COMMAND_DLQ);
  public static final Topology EVENT = new Topology(
      MessagingNames.EVENT_RETRY_1, MessagingNames.EVENT_RETRY_2, MessagingNames.EVENT_DLQ);
  public static final Topology RESULT = new Topology(
      MessagingNames.RESULT_RETRY_1, MessagingNames.RESULT_RETRY_2, MessagingNames.RESULT_DLQ);
  public static final Topology QUERY = new Topology(
      MessagingNames.QUERY_RETRY_1, MessagingNames.QUERY_RETRY_2, MessagingNames.QUERY_DLQ);

  private final ConfirmedRabbitPublisher publisher;

  public FailureRouter(ConfirmedRabbitPublisher publisher) { this.publisher = publisher; }

  public void invalid(Message original, Topology topology) { publish(original, topology.dlq(), attempt(original)); }

  public void afterTechnicalFailure(Message original, Topology topology, boolean transientFailure) {
    int attempt = attempt(original);
    if (transientFailure && attempt < AmqpNames.MAX_ATTEMPTS) {
      publish(original, attempt == 1 ? topology.retry1() : topology.retry2(), attempt + 1);
    } else {
      publish(original, topology.dlq(), attempt);
    }
  }

  private void publish(Message original, String routingKey, int attempt) {
    MessageProperties props = new MessageProperties();
    props.setContentType(original.getMessageProperties().getContentType());
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    props.setMessageId(original.getMessageProperties().getMessageId());
    original.getMessageProperties().getHeaders().forEach(props::setHeader);
    props.setHeader(AmqpNames.ATTEMPT_HEADER, attempt);
    publisher.publishRequiredRoute(
        MessagingNames.DLX_EXCHANGE, routingKey, new Message(original.getBody(), props));
  }

  private static int attempt(Message message) {
    Object raw = message.getMessageProperties().getHeader(AmqpNames.ATTEMPT_HEADER);
    return raw instanceof Number number ? Math.max(1, number.intValue()) : 1;
  }
}

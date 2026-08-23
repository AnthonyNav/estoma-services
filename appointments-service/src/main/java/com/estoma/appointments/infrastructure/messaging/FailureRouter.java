package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.contracts.MessagingNames;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.stereotype.Component;

/** Reroutes a delivery only after the retry/DLQ publish is broker-confirmed. */
@Component
public final class FailureRouter {
  private final ConfirmedRabbitPublisher publisher;

  public FailureRouter(ConfirmedRabbitPublisher publisher) {
    this.publisher = publisher;
  }

  public Route route(Message message, String queue, boolean transientFailure) {
    int attempt = headerInt(message, "x-appointments-attempt");
    String key;
    Route route;
    if (transientFailure && attempt == 0) {
      key = retryKey(queue, 1);
      route = Route.RETRY;
    } else if (transientFailure && attempt == 1) {
      key = retryKey(queue, 2);
      route = Route.RETRY;
    } else {
      key = dlqKey(queue);
      route = Route.DLQ;
    }

    MessageProperties properties = MessagePropertiesBuilder
        .fromClonedProperties(message.getMessageProperties())
        .setHeader("x-appointments-attempt", attempt + 1)
        .build();
    publisher.publishRequiredRoute(MessagingNames.DLX_EXCHANGE, key,
        new Message(message.getBody(), properties));
    return route;
  }

  private static String retryKey(String queue, int attempt) { return queue + ".retry." + attempt; }
  private static String dlqKey(String queue) { return queue + ".dlq"; }
  private static int headerInt(Message message, String key) {
    Object value = message.getMessageProperties().getHeaders().get(key);
    return value instanceof Number number ? number.intValue() : 0;
  }

  public enum Route { RETRY, DLQ }
}

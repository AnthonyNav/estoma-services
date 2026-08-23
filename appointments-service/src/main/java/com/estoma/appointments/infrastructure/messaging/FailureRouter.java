package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.contracts.MessagingNames;
import java.util.Map;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class FailureRouter {
  private final RabbitTemplate template;
  public FailureRouter(@Qualifier("requiredRouteRabbitTemplate") RabbitTemplate template) { this.template = template; }

  public Route route(Message message, String queue, boolean transientFailure) {
    int attempt = headerInt(message, "x-appointments-attempt");
    String key;
    Route route;
    if (transientFailure && attempt == 0) { key = retryKey(queue, 1); route = Route.RETRY; }
    else if (transientFailure && attempt == 1) { key = retryKey(queue, 2); route = Route.RETRY; }
    else { key = dlqKey(queue); route = Route.DLQ; }
    MessageProperties properties = new MessageProperties();
    properties.setHeaders(new java.util.HashMap<>(message.getMessageProperties().getHeaders()));
    properties.setHeader("x-appointments-attempt", attempt + 1);
    template.send(MessagingNames.DLX_EXCHANGE, key, new Message(message.getBody(), properties));
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

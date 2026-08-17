package com.estoma.authorization.infrastructure.messaging.outbound;

import org.springframework.amqp.core.Message;

public interface ConfirmedRabbitPublisher {

  void publishRequiredRoute(String exchange, String routingKey, Message message);

  void publishEvent(String exchange, String routingKey, Message message);
}

package com.estoma.appointments.infrastructure.messaging;

import org.springframework.amqp.core.Message;

public interface ConfirmedRabbitPublisher {
  void publishRequiredRoute(String exchange, String routingKey, Message message);
  void publishEvent(String exchange, String routingKey, Message message);
}

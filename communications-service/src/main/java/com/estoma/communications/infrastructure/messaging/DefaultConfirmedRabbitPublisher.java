package com.estoma.communications.infrastructure.messaging;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class DefaultConfirmedRabbitPublisher implements ConfirmedRabbitPublisher {

  private final RabbitTemplate rabbitTemplate;

  public DefaultConfirmedRabbitPublisher(RabbitTemplate communicationsRabbitTemplate) {
    this.rabbitTemplate = communicationsRabbitTemplate;
  }

  @Override
  public void publishRequiredRoute(String exchange, String routingKey, Message message) {
    publish(exchange, routingKey, message, true);
  }

  @Override
  public void publishEvent(String exchange, String routingKey, Message message) {
    publish(exchange, routingKey, message, false);
  }

  private void publish(String exchange, String routingKey, Message message, boolean requireRoute) {
    CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
    try {
      rabbitTemplate.send(exchange, routingKey, message, correlation);
      CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
      if (!confirm.ack()) {
        throw new IllegalStateException("RabbitMQ publisher NACK: " + confirm.reason());
      }
      if (requireRoute && correlation.getReturned() != null) {
        throw new IllegalStateException("RabbitMQ publication was returned as unroutable");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("RabbitMQ publisher confirm interrupted", interrupted);
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("RabbitMQ publication was not confirmed", exception);
    }
  }
}

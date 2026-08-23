package com.estoma.washexecution.infrastructure.messaging.outbound;

import com.estoma.washexecution.infrastructure.messaging.MessagingProperties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class DefaultConfirmedRabbitPublisher implements ConfirmedRabbitPublisher {
  private final RabbitTemplate requiredRouteTemplate;
  private final RabbitTemplate eventTemplate;
  private final MessagingProperties properties;

  public DefaultConfirmedRabbitPublisher(
      @Qualifier("requiredRouteRabbitTemplate") RabbitTemplate requiredRouteTemplate,
      @Qualifier("eventRabbitTemplate") RabbitTemplate eventTemplate,
      MessagingProperties properties) {
    this.requiredRouteTemplate = requiredRouteTemplate;
    this.eventTemplate = eventTemplate;
    this.properties = properties;
  }

  @Override public void publishRequiredRoute(String exchange, String routingKey, Message message) {
    confirm(requiredRouteTemplate, exchange, routingKey, message, true);
  }

  @Override public void publishEvent(String exchange, String routingKey, Message message) {
    confirm(eventTemplate, exchange, routingKey, message, false);
  }

  private void confirm(RabbitTemplate template, String exchange, String routingKey, Message message, boolean required) {
    CorrelationData data = new CorrelationData(UUID.randomUUID().toString());
    try {
      template.send(exchange, routingKey, message, data);
      CorrelationData.Confirm confirm = data.getFuture().get(
          properties.publisherConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!confirm.ack()) throw new RabbitPublishException("RABBIT_PUBLISH_NACK");
      if (required && data.getReturned() != null) throw new RabbitPublishException("RABBIT_PUBLISH_RETURNED");
    } catch (TimeoutException ex) {
      throw new RabbitPublishException("RABBIT_PUBLISH_TIMEOUT", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new RabbitPublishException("RABBIT_PUBLISH_INTERRUPTED", ex);
    } catch (ExecutionException | AmqpException ex) {
      throw new RabbitPublishException("RABBIT_PUBLISH_FAILURE", ex);
    }
  }
}

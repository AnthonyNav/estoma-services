package com.estoma.appointments.infrastructure.messaging;

import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.ShutdownSignalException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class DefaultConfirmedRabbitPublisher implements ConfirmedRabbitPublisher {
  private final RabbitTemplate required;
  private final RabbitTemplate events;
  private final MessagingProperties properties;

  public DefaultConfirmedRabbitPublisher(@Qualifier("requiredRouteRabbitTemplate") RabbitTemplate required,
      @Qualifier("eventRabbitTemplate") RabbitTemplate events, MessagingProperties properties) {
    this.required = required; this.events = events; this.properties = properties;
  }
  @Override public void publishRequiredRoute(String exchange, String routingKey, Message message) { confirm(required, exchange, routingKey, message, true); }
  @Override public void publishEvent(String exchange, String routingKey, Message message) { confirm(events, exchange, routingKey, message, false); }

  private void confirm(RabbitTemplate template, String exchange, String routingKey, Message message, boolean requiredRoute) {
    UUID attempt = UUID.randomUUID();
    CorrelationData correlation = new CorrelationData(attempt.toString());
    try {
      template.send(exchange, routingKey, message, correlation);
      CorrelationData.Confirm result = correlation.getFuture().get(properties.publisherConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!result.ack()) throw new RabbitPublishException("publisher confirm NACK");
      if (requiredRoute && correlation.getReturned() != null) throw new RabbitPublishException("required route returned");
    } catch (RabbitPublishException exception) { throw exception; }
    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new RabbitPublishException("publisher confirm interrupted", exception); }
    catch (Exception exception) { throw new RabbitPublishException(classify(exception), exception); }
  }
  private static String classify(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof AmqpConnectException || cause instanceof AmqpIOException
          || cause instanceof ShutdownSignalException || cause instanceof AlreadyClosedException) return "BROKER_UNAVAILABLE";
    }
    return "PUBLISH_FAILURE";
  }
}

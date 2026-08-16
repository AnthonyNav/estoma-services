package com.estoma.washadministration.infrastructure.messaging.outbound;

import com.estoma.washadministration.infrastructure.messaging.MessagingProperties;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.ShutdownSignalException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class DefaultConfirmedRabbitPublisher implements ConfirmedRabbitPublisher {

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

  @Override
  public void publishRequiredRoute(String exchange, String routingKey, Message message) {
    confirm(requiredRouteTemplate, exchange, routingKey, message, true);
  }

  @Override
  public void publishEvent(String exchange, String routingKey, Message message) {
    confirm(eventTemplate, exchange, routingKey, message, false);
  }

  private void confirm(
      RabbitTemplate template, String exchange, String routingKey, Message message, boolean requiredRoute) {
    UUID attemptId = UUID.randomUUID();
    CorrelationData correlationData = new CorrelationData(attemptId.toString());
    try {
      template.send(exchange, routingKey, message, correlationData);
    } catch (AmqpException exception) {
      throw failure(OutboxFailureCode.PUBLISH_CONNECTION_FAILURE, attemptId, exception);
    }

    CorrelationData.Confirm confirm;
    try {
      confirm = correlationData.getFuture().get(properties.publisherConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      throw failure(OutboxFailureCode.PUBLISH_TIMEOUT, attemptId, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw failure(OutboxFailureCode.PUBLISH_INTERRUPTED, attemptId, exception);
    } catch (ExecutionException exception) {
      throw failure(classify(exception), attemptId, exception);
    }
    if (!confirm.ack()) {
      throw failure(OutboxFailureCode.PUBLISH_NACK, attemptId, null);
    }
    if (requiredRoute && correlationData.getReturned() != null) {
      throw failure(OutboxFailureCode.PUBLISH_RETURNED, attemptId, null);
    }
  }

  private static OutboxFailureCode classify(ExecutionException exception) {
    for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof AmqpConnectException
          || cause instanceof AmqpIOException
          || cause instanceof ShutdownSignalException
          || cause instanceof AlreadyClosedException) {
        return OutboxFailureCode.PUBLISH_CONNECTION_FAILURE;
      }
    }
    return OutboxFailureCode.PUBLISH_UNEXPECTED_ERROR;
  }

  private static RabbitPublishException failure(
      OutboxFailureCode code, UUID attemptId, Throwable cause) {
    return new RabbitPublishException(code, attemptId, cause);
  }
}

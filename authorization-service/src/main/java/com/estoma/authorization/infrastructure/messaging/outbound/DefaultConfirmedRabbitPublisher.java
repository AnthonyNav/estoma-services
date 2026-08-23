package com.estoma.authorization.infrastructure.messaging.outbound;

import com.estoma.authorization.infrastructure.messaging.MessagingProperties;
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
final class DefaultConfirmedRabbitPublisher implements ConfirmedRabbitPublisher {

  private final RabbitTemplate requiredRouteTemplate;
  private final RabbitTemplate eventTemplate;
  private final MessagingProperties properties;

  DefaultConfirmedRabbitPublisher(
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
    CorrelationData correlation = new CorrelationData(attemptId.toString());
    try {
      template.send(exchange, routingKey, message, correlation);
      CorrelationData.Confirm confirm = correlation.getFuture().get(
          properties.publisherConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!confirm.ack()) {
        throw failure(PublishFailureCode.PUBLISH_NACK, attemptId, null);
      }
      if (requiredRoute && correlation.getReturned() != null) {
        throw failure(PublishFailureCode.PUBLISH_RETURNED, attemptId, null);
      }
    } catch (RabbitPublishException exception) {
      throw exception;
    } catch (TimeoutException exception) {
      throw failure(PublishFailureCode.PUBLISH_TIMEOUT, attemptId, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw failure(PublishFailureCode.PUBLISH_INTERRUPTED, attemptId, exception);
    } catch (ExecutionException exception) {
      throw failure(classify(exception), attemptId, exception);
    } catch (AmqpException exception) {
      throw failure(PublishFailureCode.PUBLISH_CONNECTION_FAILURE, attemptId, exception);
    }
  }

  private static PublishFailureCode classify(ExecutionException exception) {
    for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof AmqpConnectException
          || cause instanceof AmqpIOException
          || cause instanceof ShutdownSignalException
          || cause instanceof AlreadyClosedException) {
        return PublishFailureCode.PUBLISH_CONNECTION_FAILURE;
      }
    }
    return PublishFailureCode.PUBLISH_UNEXPECTED_ERROR;
  }

  private static RabbitPublishException failure(
      PublishFailureCode code, UUID attemptId, Throwable cause) {
    return new RabbitPublishException(code, attemptId, cause);
  }
}

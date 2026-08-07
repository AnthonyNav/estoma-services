package com.estoma.users.messaging.outbound;

import com.estoma.users.config.MessagingProperties;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.ShutdownSignalException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Default {@link ConfirmedRabbitPublisher}. Every publication gets its own
 * random {@code publishAttemptId} — deliberately NOT the wire {@code
 * messageId} (docs/system-contract.md §7): a stale worker can legitimately
 * republish the same {@code messageId} after losing its Outbox lease, and
 * two in-flight {@link CorrelationData} sharing one id would cross their
 * confirms/returns. {@code publishAttemptId} exists purely to correlate
 * one attempt against RabbitMQ — it never appears on the wire.
 *
 * <p>Uses two injected templates, selected explicitly by qualifier rather
 * than relying on {@code @Primary} — the productive code path must not
 * depend on which template Spring happens to prefer.
 */
@Component
public class DefaultConfirmedRabbitPublisher implements ConfirmedRabbitPublisher {

  private static final Logger log = LoggerFactory.getLogger(DefaultConfirmedRabbitPublisher.class);

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
      RabbitTemplate template, String exchange, String routingKey, Message message, boolean requireRoute) {
    UUID publishAttemptId = UUID.randomUUID();
    CorrelationData correlationData = new CorrelationData(publishAttemptId.toString());

    try {
      template.send(exchange, routingKey, message, correlationData);
    } catch (AmqpException e) {
      throw fail(OutboxFailureCode.PUBLISH_CONNECTION_FAILURE, publishAttemptId, e, exchange, routingKey);
    }

    CorrelationData.Confirm confirm;
    try {
      confirm =
          correlationData
              .getFuture()
              .get(properties.publisherConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw fail(OutboxFailureCode.PUBLISH_TIMEOUT, publishAttemptId, e, exchange, routingKey);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw fail(OutboxFailureCode.PUBLISH_INTERRUPTED, publishAttemptId, e, exchange, routingKey);
    } catch (ExecutionException e) {
      throw fail(classifyExecutionFailure(e), publishAttemptId, e, exchange, routingKey);
    }

    if (!confirm.ack()) {
      throw fail(OutboxFailureCode.PUBLISH_NACK, publishAttemptId, null, exchange, routingKey);
    }

    if (requireRoute && correlationData.getReturned() != null) {
      throw fail(OutboxFailureCode.PUBLISH_RETURNED, publishAttemptId, null, exchange, routingKey);
    }
  }

  /**
   * A future completed exceptionally does not necessarily mean a
   * connection problem — inspect the cause chain instead of assuming.
   * Only causes that are clearly connection/channel failures classify as
   * {@code PUBLISH_CONNECTION_FAILURE}; anything else is {@code
   * PUBLISH_UNEXPECTED_ERROR} rather than being mislabeled.
   */
  private static OutboxFailureCode classifyExecutionFailure(ExecutionException e) {
    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof AmqpConnectException
          || cause instanceof AmqpIOException
          || cause instanceof ShutdownSignalException
          || cause instanceof AlreadyClosedException) {
        return OutboxFailureCode.PUBLISH_CONNECTION_FAILURE;
      }
    }
    return OutboxFailureCode.PUBLISH_UNEXPECTED_ERROR;
  }

  private static RabbitPublishException fail(
      OutboxFailureCode code, UUID publishAttemptId, Throwable cause, String exchange, String routingKey) {
    log.error(
        "rabbit publication not confirmed; publishAttemptId={} exchange={} routingKey={} errorCode={}",
        publishAttemptId,
        exchange,
        routingKey,
        code,
        cause);
    return new RabbitPublishException(code, publishAttemptId, cause);
  }
}

package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.contracts.messaging.CommandEnvelope;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.domain.messaging.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DefaultConfirmedCommandPublisher implements ConfirmedCommandPublisher {
  private final RabbitTemplate rabbitTemplate;
  private final MessageEnvelopeCodec codec;
  private final long confirmTimeoutMillis;

  public DefaultConfirmedCommandPublisher(RabbitTemplate rabbitTemplate, MessageEnvelopeCodec codec,
      @Value("${platform.outbox.confirm-timeout:8s}") java.time.Duration confirmTimeout) {
    this.rabbitTemplate = rabbitTemplate;
    this.codec = codec;
    this.confirmTimeoutMillis = confirmTimeout.toMillis();
  }

  @Override
  public void publish(OutboxMessage message) {
    CommandEnvelope envelope = new CommandEnvelope(message.messageId(), message.messageType(),
        message.messageVersion(), MessageKind.COMMAND, "bff", message.createdAt(),
        message.correlationId(), message.causationId(), message.operationId(), message.idempotencyKey(),
        message.aggregateId(), message.aggregateVersion(), codec.readActor(message.actorJson()),
        codec.readTree(message.payloadJson()));
    var properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setMessageId(message.messageId().toString());
    Message rabbitMessage = new Message(codec.write(envelope).getBytes(StandardCharsets.UTF_8), properties);
    var correlationData = new CorrelationData(UUID.randomUUID().toString());
    try {
      rabbitTemplate.send(message.exchangeName(), message.routingKey(), rabbitMessage, correlationData);
      var confirmation = correlationData.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
      if (!confirmation.ack()) {
        throw new IllegalStateException("RabbitMQ publisher confirm was NACK");
      }
      if (correlationData.getReturned() != null) {
        throw new IllegalStateException("RabbitMQ command was unroutable");
      }
    } catch (TimeoutException timeout) {
      throw new IllegalStateException("RabbitMQ publisher confirm timed out", timeout);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("RabbitMQ publisher was interrupted", interrupted);
    } catch (AmqpException failure) {
      throw new IllegalStateException("RabbitMQ command publication failed", failure);
    } catch (java.util.concurrent.ExecutionException failure) {
      throw new IllegalStateException("RabbitMQ publisher confirm failed", failure);
    }
  }
}

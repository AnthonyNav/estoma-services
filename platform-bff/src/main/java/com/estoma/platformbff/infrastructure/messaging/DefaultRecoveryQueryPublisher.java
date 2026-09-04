package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.contracts.messaging.QueryEnvelope;
import com.estoma.platformbff.domain.projection.RecoveryQuery;
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
public class DefaultRecoveryQueryPublisher implements RecoveryQueryPublisher {
  private final RabbitTemplate rabbit;
  private final MessageEnvelopeCodec codec;
  private final long timeoutMillis;

  public DefaultRecoveryQueryPublisher(RabbitTemplate rabbit, MessageEnvelopeCodec codec,
      @Value("${platform.outbox.confirm-timeout:8s}") java.time.Duration timeout) {
    this.rabbit = rabbit;
    this.codec = codec;
    this.timeoutMillis = timeout.toMillis();
  }

  @Override
  public void publish(RecoveryQuery query) {
    // Owner services validate recovery queries as BFF technical work. Older recovery rows did
    // not persist an actor; treating those as anonymous made every owner reject the request.
    ActorContext actor = query.actorJson() == null ? ActorContext.system("bff") : codec.readActor(query.actorJson());
    QueryEnvelope envelope = new QueryEnvelope(query.messageId(), query.messageType(), query.messageVersion(),
        MessageKind.QUERY, "bff", query.createdAt(), query.correlationId(), query.causationId(),
        query.recoveryJobId(), null, null, null, actor,
        codec.readTree(codec.write(query.payload())));
    var properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setMessageId(query.messageId().toString());
    Message message = new Message(codec.write(envelope).getBytes(StandardCharsets.UTF_8), properties);
    var confirmation = new CorrelationData(UUID.randomUUID().toString());
    try {
      rabbit.send(query.exchangeName(), query.routingKey(), message, confirmation);
      var result = confirmation.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
      if (!result.ack()) throw new IllegalStateException("RabbitMQ recovery Query was NACKed");
      if (confirmation.getReturned() != null) throw new IllegalStateException("RabbitMQ recovery Query was unroutable");
    } catch (TimeoutException timeout) {
      throw new IllegalStateException("RabbitMQ recovery Query confirm timed out", timeout);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("RabbitMQ recovery Query publisher interrupted", interrupted);
    } catch (AmqpException | java.util.concurrent.ExecutionException failure) {
      throw new IllegalStateException("RabbitMQ recovery Query publication failed", failure);
    }
  }
}

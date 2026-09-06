package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.port.ReassignmentCandidatesPort.Refresh;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.contracts.messaging.QueryEnvelope;
import com.estoma.platformbff.infrastructure.config.MessagingTopology;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
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

/** Confirmed publisher for the asynchronous Wash Execution owner Query. */
@Component
public class ReassignmentCandidatesQueryPublisher {
  private final RabbitTemplate rabbit;
  private final MessageEnvelopeCodec codec;
  private final long timeoutMillis;

  public ReassignmentCandidatesQueryPublisher(RabbitTemplate rabbit, MessageEnvelopeCodec codec,
      @Value("${platform.outbox.confirm-timeout:8s}") Duration timeout) {
    this.rabbit = rabbit;
    this.codec = codec;
    this.timeoutMillis = timeout.toMillis();
  }

  public void publish(Refresh refresh) {
    QueryEnvelope envelope = new QueryEnvelope(refresh.messageId(), "GetReassignmentCandidates", 1,
        MessageKind.QUERY, "bff", refresh.requestedAt(), refresh.correlationId(), null, null, null,
        null, null, ActorContext.system("bff"), codec.readTree(codec.write(
            Map.of("washExecutionId", refresh.washExecutionId()))));
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setMessageId(refresh.messageId().toString());
    Message message = new Message(codec.write(envelope).getBytes(StandardCharsets.UTF_8), properties);
    CorrelationData confirmation = new CorrelationData(UUID.randomUUID().toString());
    try {
      rabbit.send(MessagingTopology.QUERIES_EXCHANGE, "wash-execution", message, confirmation);
      var result = confirmation.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
      if (!result.ack()) throw new IllegalStateException("reassignment candidates Query was NACKed");
      if (confirmation.getReturned() != null) {
        throw new IllegalStateException("reassignment candidates Query was unroutable");
      }
    } catch (TimeoutException timeout) {
      throw new IllegalStateException("reassignment candidates Query confirm timed out", timeout);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("reassignment candidates Query publisher interrupted", interrupted);
    } catch (AmqpException | java.util.concurrent.ExecutionException failure) {
      throw new IllegalStateException("reassignment candidates Query publication failed", failure);
    }
  }
}

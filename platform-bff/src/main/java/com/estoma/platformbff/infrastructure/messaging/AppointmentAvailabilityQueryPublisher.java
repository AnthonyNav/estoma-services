package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Refresh;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.contracts.messaging.QueryEnvelope;
import com.estoma.platformbff.infrastructure.config.MessagingTopology;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
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

/** Confirmed publisher for the explicitly owner-approved asynchronous availability Query. */
@Component
public class AppointmentAvailabilityQueryPublisher {
  private final RabbitTemplate rabbit;
  private final MessageEnvelopeCodec codec;
  private final long timeoutMillis;

  public AppointmentAvailabilityQueryPublisher(RabbitTemplate rabbit, MessageEnvelopeCodec codec,
      @Value("${platform.outbox.confirm-timeout:8s}") Duration timeout) {
    this.rabbit = rabbit;
    this.codec = codec;
    this.timeoutMillis = timeout.toMillis();
  }

  public void publish(Refresh refresh) {
    var context = refresh.context();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("studentAccountId", context.studentAccountId());
    payload.put("serviceDate", context.serviceDate());
    payload.put("appointmentType", context.appointmentType());
    payload.put("instrumentCount", context.instrumentCount());
    payload.put("pieceType", context.pieceType());
    payload.put("courseSectionId", context.courseSectionId());
    QueryEnvelope envelope = new QueryEnvelope(refresh.messageId(), "GetAppointmentAvailability", 1,
        MessageKind.QUERY, "bff", refresh.requestedAt(), refresh.correlationId(), null, null, null,
        null, null, ActorContext.system("bff"), codec.readTree(codec.write(payload)));
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setMessageId(refresh.messageId().toString());
    Message message = new Message(codec.write(envelope).getBytes(StandardCharsets.UTF_8), properties);
    CorrelationData confirmation = new CorrelationData(UUID.randomUUID().toString());
    try {
      rabbit.send(MessagingTopology.QUERIES_EXCHANGE, "appointments", message, confirmation);
      var result = confirmation.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
      if (!result.ack()) throw new IllegalStateException("availability Query was NACKed");
      if (confirmation.getReturned() != null) {
        throw new IllegalStateException("availability Query was unroutable");
      }
    } catch (TimeoutException timeout) {
      throw new IllegalStateException("availability Query confirm timed out", timeout);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("availability Query publisher interrupted", interrupted);
    } catch (AmqpException | java.util.concurrent.ExecutionException failure) {
      throw new IllegalStateException("availability Query publication failed", failure);
    }
  }
}

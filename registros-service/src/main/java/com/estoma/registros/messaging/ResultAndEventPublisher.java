package com.estoma.registros.messaging;

import com.estoma.registros.contract.Actor;
import com.estoma.registros.contract.MessageEnvelope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** Publishes correlated Results (platform.results) and domain Events (platform.events). */
@Component
public class ResultAndEventPublisher {

  private static final Actor REGISTROS_SYSTEM_ACTOR = new Actor("SYSTEM", null, AmqpNames.LOGICAL_COMPONENT_ID);

  private final RabbitTemplate rabbitTemplate;
  private final EnvelopeCodec codec;

  public ResultAndEventPublisher(RabbitTemplate rabbitTemplate, EnvelopeCodec codec) {
    this.rabbitTemplate = rabbitTemplate;
    this.codec = codec;
  }

  public void publishSucceededResult(
      String resultMessageType, MessageEnvelope command, Object data, UUID aggregateId, Integer aggregateVersion) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "SUCCEEDED");
    payload.put("data", data);
    sendResult(resultMessageType, command, aggregateId, aggregateVersion, payload);
  }

  public void publishRejectedResult(String resultMessageType, MessageEnvelope command, String errorCode, String message) {
    sendResult(
        resultMessageType,
        command,
        command.aggregateId(),
        command.aggregateVersion(),
        statusWithError("REJECTED", errorCode, message));
  }

  public void publishFailedResult(String resultMessageType, MessageEnvelope command, String errorCode, String message) {
    sendResult(
        resultMessageType,
        command,
        command.aggregateId(),
        command.aggregateVersion(),
        statusWithError("FAILED", errorCode, message));
  }

  private static Map<String, Object> statusWithError(String status, String errorCode, String message) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("code", errorCode);
    error.put("details", Map.of("message", message == null ? "" : message));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", status);
    payload.put("error", error);
    return payload;
  }

  private void sendResult(
      String resultMessageType, MessageEnvelope command, UUID aggregateId, Integer aggregateVersion, Object payload) {
    MessageEnvelope result =
        new MessageEnvelope(
            UUID.randomUUID(),
            resultMessageType,
            1,
            "RESULT",
            AmqpNames.LOGICAL_COMPONENT_ID,
            Instant.now(),
            command.correlationId(),
            command.messageId(),
            command.operationId(),
            null,
            aggregateId,
            aggregateVersion,
            REGISTROS_SYSTEM_ACTOR,
            codec.valueToTree(payload));
    send(AmqpNames.EXCHANGE_RESULTS, AmqpNames.ROUTING_KEY_RESULTS_BFF, result);
  }

  public void publishEvent(
      String eventMessageType,
      String routingKey,
      UUID correlationId,
      UUID causationId,
      UUID aggregateId,
      int aggregateVersion,
      Object payload) {
    MessageEnvelope event =
        new MessageEnvelope(
            UUID.randomUUID(),
            eventMessageType,
            1,
            "EVENT",
            AmqpNames.LOGICAL_COMPONENT_ID,
            Instant.now(),
            correlationId,
            causationId,
            null,
            null,
            aggregateId,
            aggregateVersion,
            REGISTROS_SYSTEM_ACTOR,
            codec.valueToTree(payload));
    send(AmqpNames.EXCHANGE_EVENTS, routingKey, event);
  }

  private void send(String exchange, String routingKey, MessageEnvelope envelope) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType("application/json");
    properties.setMessageId(envelope.messageId().toString());
    rabbitTemplate.send(exchange, routingKey, new Message(codec.encode(envelope), properties));
  }
}

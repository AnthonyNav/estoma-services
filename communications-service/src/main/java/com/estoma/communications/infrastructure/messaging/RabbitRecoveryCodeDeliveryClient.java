package com.estoma.communications.infrastructure.messaging;

import com.estoma.communications.application.port.RecoveryCodeDeliveryPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Direct Reply-To client for Authentication.GetRecoveryCodeForDelivery.
 * Never logs or persists the request/response body or returned code.
 */
@Component
public class RabbitRecoveryCodeDeliveryClient implements RecoveryCodeDeliveryPort {

  static final String EXCHANGE = "platform.security.rpc";
  static final String ROUTING_KEY = "rpc.authentication";
  static final String OPERATION = "GetRecoveryCodeForDelivery";

  private final RabbitTemplate rabbit;
  private final ObjectMapper mapper;

  RabbitRecoveryCodeDeliveryClient(
      @Qualifier("recoverySecurityRpcTemplate") RabbitTemplate rabbit,
      ObjectMapper mapper) {
    this.rabbit = rabbit;
    this.mapper = mapper;
  }

  @Override
  public String getCode(UUID recoveryId, UUID correlationId) {
    if (recoveryId == null) throw new IllegalArgumentException("recoveryId is required");
    UUID requestId = UUID.randomUUID();
    UUID flowId = correlationId == null ? UUID.randomUUID() : correlationId;

    Message request = new Message(encodeRequest(requestId, flowId, recoveryId), properties(requestId, flowId));
    final Message reply;
    try {
      reply = rabbit.sendAndReceive(EXCHANGE, ROUTING_KEY, request);
    } catch (RuntimeException technical) {
      throw new TechnicalException("recovery Security RPC failed before a response was available", technical);
    }
    if (reply == null) {
      throw new TechnicalException("recovery Security RPC timed out");
    }

    try {
      JsonNode envelope = mapper.readTree(reply.getBody());
      validateReply(envelope, requestId, flowId);
      JsonNode payload = envelope.path("payload");
      String status = payload.path("status").asString();
      if ("SUCCEEDED".equals(status)) {
        String code = payload.path("data").path("code").asString();
        if (code == null || code.isBlank()) throw new TechnicalException("recovery Security RPC success response omitted code");
        return code;
      }
      if ("REJECTED".equals(status)) {
        String ownerCode = payload.path("error").path("code").asString();
        if ("RECOVERY_CODE_EXPIRED".equals(ownerCode)) {
          throw new NotAvailableException("RECOVERY_EXPIRED");
        }
        throw new NotAvailableException("RECOVERY_NOT_AVAILABLE");
      }
      if ("FAILED".equals(status)) {
        throw new TechnicalException("Authentication reported a technical recovery RPC failure");
      }
      throw new TechnicalException("recovery Security RPC returned an unsupported status");
    } catch (NotAvailableException | TechnicalException expected) {
      throw expected;
    } catch (RuntimeException malformed) {
      throw new TechnicalException("recovery Security RPC response was malformed", malformed);
    }
  }

  private byte[] encodeRequest(UUID messageId, UUID correlationId, UUID recoveryId) {
    ObjectNode root = mapper.createObjectNode();
    root.put("messageId", messageId.toString());
    root.put("messageType", OPERATION);
    root.put("messageVersion", 1);
    root.put("messageKind", "RPC_REQUEST");
    root.put("producer", "communications");
    root.put("occurredAt", Instant.now().toString());
    root.put("correlationId", correlationId.toString());

    ObjectNode actor = root.putObject("actor");
    actor.put("type", "SYSTEM");
    actor.put("service", "communications");
    actor.put("scope", "GLOBAL");

    root.putObject("payload").put("recoveryId", recoveryId.toString());
    return mapper.writeValueAsBytes(root);
  }

  private static MessageProperties properties(UUID messageId, UUID correlationId) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setMessageId(messageId.toString());
    properties.setCorrelationId(correlationId.toString());
    properties.setExpiration("5000");
    return properties;
  }

  private static void validateReply(JsonNode envelope, UUID requestId, UUID correlationId) {
    if (envelope == null || !"RPC_RESPONSE".equals(envelope.path("messageKind").asString())
        || !OPERATION.equals(envelope.path("messageType").asString())
        || !"authentication".equals(envelope.path("producer").asString())
        || !correlationId.toString().equals(envelope.path("correlationId").asString())
        || !requestId.toString().equals(envelope.path("causationId").asString())
        || !envelope.path("payload").isObject()) {
      throw new TechnicalException("recovery Security RPC response failed correlation validation");
    }
  }
}

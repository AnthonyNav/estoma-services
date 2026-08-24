package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.security.SecurityRpcPort;
import com.estoma.platformbff.application.security.SecurityRpcUnavailableException;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.contracts.messaging.ResultEnvelope;
import com.estoma.platformbff.contracts.messaging.RpcRequestEnvelope;
import com.estoma.platformbff.infrastructure.config.MessagingTopology;
import com.estoma.platformbff.infrastructure.config.PlatformProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class SecurityRpcClient implements SecurityRpcPort {
  private static final Logger log = LoggerFactory.getLogger(SecurityRpcClient.class);
  private static final Set<String> ALLOWLIST = Set.of(
      "AuthenticateAccount",
      "RefreshSession",
      "RevokeSession",
      "ValidateRecoveryCode",
      "ResetPassword",
      "ChangePassword",
      "SetTemporaryPassword",
      "RegenerateTemporaryPassword");

  private final RabbitTemplate rabbit;
  private final MessageEnvelopeCodec codec;
  private final Duration timeout;
  private final Duration requestTtl;

  public SecurityRpcClient(
      @Qualifier("securityRpcRabbitTemplate") RabbitTemplate rabbit,
      MessageEnvelopeCodec codec,
      PlatformProperties properties) {
    this.rabbit = rabbit;
    this.codec = codec;
    this.timeout = properties.securityRpc().timeout();
    this.requestTtl = properties.securityRpc().requestTtl();
    if (properties.securityRpc().retryAttempts() != 0) {
      throw new IllegalStateException("Security RPC automatic retry must remain zero");
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()
        || requestTtl == null || requestTtl.isZero() || requestTtl.isNegative()
        || timeout.compareTo(requestTtl) >= 0) {
      throw new IllegalStateException("Security RPC requires 0 < timeout < request TTL");
    }
  }

  @Override
  public RpcCallResult call(
      String operation,
      ActorContext actor,
      UUID targetAccountId,
      String idempotencyKey,
      String payloadJson) {
    if (!ALLOWLIST.contains(operation)) {
      throw new IllegalArgumentException("security RPC is not allowlisted");
    }
    if (actor == null || payloadJson == null || payloadJson.isBlank()) {
      throw new IllegalArgumentException("security RPC actor and payload are required");
    }

    UUID correlationId = UUID.randomUUID();
    RpcRequestEnvelope envelope = RpcRequestEnvelope.create(
        operation, correlationId, idempotencyKey, targetAccountId, actor, codec.readTree(payloadJson));
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setMessageId(envelope.messageId().toString());
    properties.setCorrelationId(correlationId.toString());
    properties.setExpiration(Long.toString(requestTtl.toMillis()));

    Message reply;
    try {
      reply = rabbit.sendAndReceive(
          MessagingTopology.SECURITY_RPC_EXCHANGE,
          MessagingTopology.AUTHENTICATION_RPC_QUEUE,
          new Message(codec.write(envelope).getBytes(StandardCharsets.UTF_8), properties));
    } catch (RuntimeException transportFailure) {
      log.warn(
          "security RPC transport failure operation={} correlationId={} category=transport",
          operation, correlationId);
      throw new SecurityRpcUnavailableException("security RPC unavailable", transportFailure);
    }
    if (reply == null) {
      log.warn(
          "security RPC timeout operation={} correlationId={} timeoutMs={}",
          operation, correlationId, timeout.toMillis());
      throw new SecurityRpcUnavailableException("security RPC timed out");
    }

    try {
      ResultEnvelope response = codec.readResult(reply.getBody());
      validateResponse(response, envelope);
      return mapPayload(response.payload());
    } catch (SecurityRpcUnavailableException protocolFailure) {
      throw protocolFailure;
    } catch (RuntimeException malformed) {
      log.warn(
          "security RPC protocol failure operation={} correlationId={} category=malformed-response",
          operation, correlationId);
      throw new SecurityRpcUnavailableException(
          "security RPC returned an invalid response", malformed);
    }
  }

  private RpcCallResult mapPayload(JsonNode payload) {
    if (payload == null || !payload.isObject()
        || !payload.hasNonNull("status") || !payload.get("status").isTextual()) {
      throw new SecurityRpcUnavailableException("security RPC response omitted status");
    }
    String status = payload.get("status").asText();
    JsonNode data = payload.get("data");
    JsonNode error = payload.get("error");
    return switch (status) {
      case "SUCCEEDED" -> {
        if (data == null || data.isNull() || (error != null && !error.isNull())) {
          throw new SecurityRpcUnavailableException("successful security RPC has invalid payload");
        }
        yield new RpcCallResult("SUCCEEDED", codec.canonical(data), null);
      }
      case "REJECTED", "FAILED" -> {
        if (error == null || !error.isObject()
            || !error.hasNonNull("code") || !error.get("code").isTextual()
            || error.get("code").asText().isBlank()) {
          throw new SecurityRpcUnavailableException("failed security RPC omitted error code");
        }
        yield new RpcCallResult(status, null, error.get("code").asText());
      }
      default -> throw new SecurityRpcUnavailableException("security RPC returned unknown status");
    };
  }

  private static void validateResponse(ResultEnvelope response, RpcRequestEnvelope request) {
    if (response == null
        || response.messageId() == null
        || response.messageKind() != MessageKind.RPC_RESPONSE
        || !request.messageType().equals(response.messageType())
        || response.messageVersion() != 1
        || !"authentication".equals(response.producer())
        || response.occurredAt() == null
        || !request.correlationId().equals(response.correlationId())
        || !request.messageId().equals(response.causationId())
        || response.operationId() != null
        || !Objects.equals(request.aggregateId(), response.aggregateId())
        || response.payload() == null) {
      throw new SecurityRpcUnavailableException("security RPC response failed trust validation");
    }
  }
}

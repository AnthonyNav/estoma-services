package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.DeferredProjectionEventDecoder;
import com.estoma.authorization.application.port.ProcessedMessageRepository.ProcessedMessage;
import com.estoma.authorization.application.projection.AccountProjectionEvent;
import com.estoma.authorization.application.projection.PersonProjectionEvent;
import com.estoma.authorization.application.projection.ProjectionEvent;
import com.estoma.authorization.application.projection.ProjectionEventMetadata;
import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class JacksonDeferredProjectionEventDecoder implements DeferredProjectionEventDecoder {

  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public ProjectionEvent decode(ProcessedMessage message) {
    if (!"EVENT".equals(message.messageKind()) || !"DEFERRED".equals(message.status())) {
      throw new IllegalArgumentException("only retained DEFERRED Events can be decoded");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = mapper.readValue(message.payloadJson(), Map.class);
    ProjectionEventMetadata metadata = new ProjectionEventMetadata(
        message.messageId(),
        message.messageType(),
        message.messageVersion(),
        message.producer(),
        message.occurredAt(),
        message.correlationId(),
        message.causationId(),
        message.operationId(),
        message.idempotencyKey(),
        message.aggregateId(),
        requireAggregateVersion(message),
        message.actor());

    return switch (message.messageType()) {
      case "PersonCreated", "PersonActivated", "PersonDeactivated" ->
          new PersonProjectionEvent(
              metadata,
              uuid(payload, "personId"),
              personStatus(message.messageType(), payload),
              message.payloadJson());
      case "AccountCreated", "AccountActivated", "AccountDeactivated" ->
          new AccountProjectionEvent(
              metadata,
              uuid(payload, "accountId"),
              uuid(payload, "personId"),
              "AccountCreated".equals(message.messageType())
                  ? LoginType.valueOf(text(payload, "loginType"))
                  : null,
              accountStatus(message.messageType(), payload),
              message.payloadJson());
      default -> throw new IllegalArgumentException(
          "messageType is not a V1 deferrable projection Event: " + message.messageType());
    };
  }

  private static int requireAggregateVersion(ProcessedMessage message) {
    if (message.aggregateVersion() == null || message.aggregateVersion() <= 0) {
      throw new IllegalArgumentException("deferred projection Event requires aggregateVersion");
    }
    return message.aggregateVersion();
  }

  private static ProjectedStatus personStatus(String messageType, Map<String, Object> payload) {
    Object status = payload.get("status");
    if (status instanceof String text) {
      return ProjectedStatus.valueOf(text);
    }
    return "PersonDeactivated".equals(messageType) ? ProjectedStatus.INACTIVE : ProjectedStatus.ACTIVE;
  }

  private static ProjectedStatus accountStatus(String messageType, Map<String, Object> payload) {
    Object status = payload.get("status");
    if (status instanceof String text) {
      return ProjectedStatus.valueOf(text);
    }
    return "AccountActivated".equals(messageType) ? ProjectedStatus.ACTIVE : ProjectedStatus.INACTIVE;
  }

  private static UUID uuid(Map<String, Object> values, String key) {
    return UUID.fromString(text(values, key));
  }

  private static String text(Map<String, Object> values, String key) {
    Object value = values.get(key);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(key + " must be non-blank text");
    }
    return text;
  }
}

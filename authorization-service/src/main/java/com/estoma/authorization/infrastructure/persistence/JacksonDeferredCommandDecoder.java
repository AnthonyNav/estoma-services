package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.command.AssignAccountRoleCommand;
import com.estoma.authorization.application.command.AuthoritativeEvidence;
import com.estoma.authorization.application.command.AuthorizationCommand;
import com.estoma.authorization.application.command.CausalDependency;
import com.estoma.authorization.application.command.CommandMetadata;
import com.estoma.authorization.application.command.GrantSystemAccessCommand;
import com.estoma.authorization.application.command.ImportExecution;
import com.estoma.authorization.application.port.DeferredCommandDecoder;
import com.estoma.authorization.application.port.ProcessedMessageRepository.ProcessedMessage;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class JacksonDeferredCommandDecoder implements DeferredCommandDecoder {

  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public DecodedCommand decode(ProcessedMessage message) {
    if (!"COMMAND".equals(message.messageKind())
        || !"academic".equals(message.producer())
        || !"DEFERRED".equals(message.status())) {
      throw new IllegalArgumentException("only retained DEFERRED Academic Commands can be decoded");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = mapper.readValue(message.payloadJson(), Map.class);
    AuthorizationCommand command = switch (message.messageType()) {
      case "AssignAccountRole" -> new AssignAccountRoleCommand(
          uuid(payload, "accountId"),
          RoleCode.valueOf(text(payload, "roleCode")),
          importExecution(object(payload, "importExecution")));
      case "GrantSystemAccess" -> new GrantSystemAccessCommand(
          uuid(payload, "systemAccessId"),
          uuid(payload, "accountId"),
          SystemCode.valueOf(text(payload, "systemCode")),
          importExecution(object(payload, "importExecution")));
      default -> throw new IllegalArgumentException(
          "messageType is not a V1 deferrable Authorization command: " + message.messageType());
    };
    CommandMetadata metadata = new CommandMetadata(
        message.messageId(),
        message.messageType(),
        message.messageVersion(),
        "COMMAND",
        message.producer(),
        message.occurredAt(),
        message.correlationId(),
        message.causationId(),
        message.operationId(),
        message.idempotencyKey(),
        message.actor(),
        message.aggregateId(),
        message.aggregateVersion());
    return new DecodedCommand(metadata, command);
  }

  private static ImportExecution importExecution(Map<String, Object> values) {
    List<CausalDependency> dependencies = new ArrayList<>();
    for (Map<String, Object> item : objectList(values, "causalDependencies")) {
      dependencies.add(new CausalDependency(
          text(item, "priorCommandType"),
          uuid(item, "priorResultMessageId"),
          text(item, "priorResultStatus"),
          text(item, "expectedEventType"),
          uuid(item, "expectedAggregateId"),
          nullableInt(item.get("minimumSourceVersion"))));
    }
    List<AuthoritativeEvidence> evidence = new ArrayList<>();
    for (Map<String, Object> item : objectList(values, "authoritativeEvidence")) {
      evidence.add(new AuthoritativeEvidence(
          text(item, "sourceService"),
          text(item, "sourceOperation"),
          text(item, "sourceResultStatus"),
          uuid(item, "sourceResultMessageId"),
          text(item, "entityType"),
          uuid(item, "entityId"),
          nullableInt(item.get("evidenceVersion"))));
    }
    return new ImportExecution(
        uuid(values, "importId"),
        uuid(values, "planItemId"),
        integer(values, "executionGeneration"),
        uuid(values, "authorizedByAccountId"),
        text(values, "scope"),
        dependencies,
        evidence);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Map<String, Object> values, String key) {
    Object value = values.get(key);
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalArgumentException(key + " must be an object");
    }
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> objectList(Map<String, Object> values, String key) {
    Object value = values.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(key + " must be an array");
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?>)) {
        throw new IllegalArgumentException(key + " items must be objects");
      }
      result.add((Map<String, Object>) item);
    }
    return result;
  }

  private static String text(Map<String, Object> values, String key) {
    Object value = values.get(key);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(key + " must be non-blank text");
    }
    return text;
  }

  private static UUID uuid(Map<String, Object> values, String key) {
    return UUID.fromString(text(values, key));
  }

  private static int integer(Map<String, Object> values, String key) {
    Object value = values.get(key);
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException(key + " must be numeric");
    }
    return number.intValue();
  }

  private static Integer nullableInt(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }
}

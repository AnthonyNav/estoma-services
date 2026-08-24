package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class EnvelopeValidator {

  private static final int SUPPORTED_MESSAGE_VERSION = 1;
  private static final Set<String> COMMAND_MESSAGE_TYPES = Arrays.stream(AuthenticationCommand.values())
      .map(AuthenticationCommand::wireName).collect(Collectors.toSet());
  private static final Set<String> ACADEMIC_TYPES = Set.of(
      AuthenticationCommand.CREATE_ACCOUNT.wireName(),
      AuthenticationCommand.ACTIVATE_ACCOUNT.wireName(),
      AuthenticationCommand.DEACTIVATE_ACCOUNT.wireName());
  private static final Map<String, String> EVENT_OWNERS = Map.of(
      "PersonCreated", "users",
      "PersonActivated", "users",
      "PersonDeactivated", "users",
      "StudentNumberCorrected", "academic");

  public void validateCommand(MessageEnvelope envelope) {
    validateCommon(envelope);
    if (envelope.messageKind() != MessageKind.COMMAND) {
      throw new InvalidMessageException(
          "expected messageKind=COMMAND on commands.authentication, got " + envelope.messageKind());
    }
    if (!COMMAND_MESSAGE_TYPES.contains(envelope.messageType())) {
      throw new InvalidMessageException("unrecognized command messageType " + envelope.messageType());
    }
    requireNonBlank(envelope.idempotencyKey(), "idempotencyKey");
    if ("academic".equals(envelope.producer())) {
      validateAcademicCommand(envelope);
    }
  }

  private static void validateAcademicCommand(MessageEnvelope envelope) {
    if (!ACADEMIC_TYPES.contains(envelope.messageType())
        || envelope.operationId() == null
        || envelope.aggregateId() == null
        || envelope.actor() == null
        || !envelope.actor().isSystemService("academic")
        || envelope.actor().accountId() != null) {
      throw new InvalidMessageException("invalid Academic coordinated command envelope");
    }
    JsonNode execution = envelope.payload().path("importExecution");
    requireUuid(execution, "importId");
    requireUuid(execution, "planItemId");
    requireUuid(execution, "authorizedByAccountId");
    if (execution.path("executionGeneration").asInt(-1) < 1) {
      throw new InvalidMessageException("importExecution.executionGeneration must be positive");
    }
  }

  public void validateEvent(MessageEnvelope envelope) {
    validateCommon(envelope);
    if (envelope.messageKind() != MessageKind.EVENT) {
      throw new InvalidMessageException(
          "expected messageKind=EVENT on events.authentication, got " + envelope.messageKind());
    }
    String owner = EVENT_OWNERS.get(envelope.messageType());
    if (owner == null) {
      throw new InvalidMessageException("unrecognized external event messageType " + envelope.messageType());
    }
    if (!owner.equals(envelope.producer())) {
      throw new InvalidMessageException(
          "event " + envelope.messageType() + " must be produced by " + owner);
    }
    if (envelope.aggregateId() == null || envelope.aggregateVersion() == null || envelope.aggregateVersion() < 1) {
      throw new InvalidMessageException("external event requires aggregateId and positive aggregateVersion");
    }
    if (envelope.payload() == null || !envelope.payload().isObject()) {
      throw new InvalidMessageException("external event payload must be an object");
    }

    UUID payloadAggregate = switch (envelope.messageType()) {
      case "PersonCreated", "PersonActivated", "PersonDeactivated" -> requireUuid(envelope.payload(), "personId");
      case "StudentNumberCorrected" -> requireUuid(envelope.payload(), "studentAcademicProfileId");
      default -> throw new InvalidMessageException("unrecognized external event messageType " + envelope.messageType());
    };
    if (!envelope.aggregateId().equals(payloadAggregate)) {
      throw new InvalidMessageException("external event aggregateId does not match owner payload aggregate");
    }
  }

  public void validateRpc(MessageEnvelope envelope, String replyTo) {
    validateCommon(envelope);
    if (envelope.messageKind() != MessageKind.RPC_REQUEST || envelope.operationId() != null
        || envelope.payload() == null || replyTo == null || replyTo.isBlank()) {
      throw new InvalidMessageException("invalid RPC envelope structure");
    }
    if (Arrays.stream(AuthenticationRpcOperation.values())
        .noneMatch(operation -> operation.wireName().equals(envelope.messageType()))) {
      throw new InvalidMessageException("unrecognized RPC messageType " + envelope.messageType());
    }
    validateActor(envelope.actor());
    if ((AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD.wireName().equals(envelope.messageType())
        || AuthenticationRpcOperation.REGENERATE_TEMPORARY_PASSWORD.wireName().equals(envelope.messageType()))
        && (envelope.idempotencyKey() == null || envelope.idempotencyKey().isBlank())) {
      throw new InvalidMessageException("idempotencyKey must not be blank for temporary-password RPC");
    }
  }

  private static void validateActor(Actor actor) {
    if (actor == null || actor.type() == null || actor.scope() == null || actor.scope().isBlank()) {
      throw new InvalidMessageException("actor is invalid");
    }
    switch (actor.type()) {
      case "ANONYMOUS" -> {
        if (actor.accountId() != null || actor.service() != null || actor.systemId() != null) {
          throw new InvalidMessageException("anonymous actor is invalid");
        }
      }
      case "ACCOUNT" -> {
        if (actor.accountId() == null || actor.service() != null) {
          throw new InvalidMessageException("account actor is invalid");
        }
      }
      case "SYSTEM" -> {
        if (actor.accountId() != null || actor.service() == null || actor.service().isBlank()) {
          throw new InvalidMessageException("system actor is invalid");
        }
      }
      default -> throw new InvalidMessageException("unrecognized actor type " + actor.type());
    }
  }

  private void validateCommon(MessageEnvelope envelope) {
    requireNonNull(envelope, "envelope");
    requireNonNull(envelope.messageId(), "messageId");
    requireNonNull(envelope.correlationId(), "correlationId");
    requireNonNull(envelope.occurredAt(), "occurredAt");
    requireNonBlank(envelope.messageType(), "messageType");
    requireNonBlank(envelope.producer(), "producer");
    requireNonNull(envelope.messageKind(), "messageKind");
    if (envelope.messageVersion() != SUPPORTED_MESSAGE_VERSION) {
      throw new InvalidMessageException(
          "unsupported messageVersion " + envelope.messageVersion() + " for " + envelope.messageType());
    }
  }

  private static UUID requireUuid(JsonNode node, String field) {
    try {
      return UUID.fromString(node.path(field).asString());
    } catch (RuntimeException exception) {
      throw new InvalidMessageException(field + " must be a UUID");
    }
  }

  private static void requireNonNull(Object value, String fieldName) {
    if (value == null) throw new InvalidMessageException(fieldName + " must not be null");
  }

  private static void requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) throw new InvalidMessageException(fieldName + " must not be blank");
  }
}

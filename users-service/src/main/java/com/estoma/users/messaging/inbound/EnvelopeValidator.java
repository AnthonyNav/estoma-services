package com.estoma.users.messaging.inbound;

import com.estoma.users.config.MessagingProperties;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class EnvelopeValidator {

  private static final int SUPPORTED_MESSAGE_VERSION = 1;
  private static final Set<String> ACADEMIC_COMMANDS = Set.of(
      MessageTypes.CREATE_PERSON, MessageTypes.UPDATE_PERSON);

  private final MessagingProperties properties;

  public EnvelopeValidator(MessagingProperties properties) {
    this.properties = properties;
  }

  public void validate(MessageEnvelope envelope) {
    requireNonNull(envelope, "envelope");
    requireNonNull(envelope.messageId(), "messageId");
    requireNonNull(envelope.occurredAt(), "occurredAt");
    requireNonNull(envelope.correlationId(), "correlationId");
    requireNonNull(envelope.payload(), "payload");
    requireNonBlank(envelope.messageType(), "messageType");
    requireNonBlank(envelope.producer(), "producer");
    requireNonNull(envelope.messageKind(), "messageKind");

    if (envelope.messageKind() != MessageKind.COMMAND) {
      throw new InvalidMessageException("expected messageKind=COMMAND on commands.users, got " + envelope.messageKind());
    }
    if (envelope.messageVersion() != SUPPORTED_MESSAGE_VERSION) {
      throw new InvalidMessageException("unsupported messageVersion " + envelope.messageVersion());
    }
    if (!MessageTypes.COMMANDS.contains(envelope.messageType())) {
      throw new InvalidMessageException("unrecognized command messageType " + envelope.messageType());
    }
    requireNonNull(envelope.aggregateId(), "aggregateId");
    requireUuid(envelope.payload(), "personId");
    if (!properties.isCommandAllowed(envelope.producer(), envelope.messageType())) {
      throw new InvalidMessageException("producer not authorized for this operation: " + envelope.producer());
    }
    if (envelope.operationId() == null) {
      throw new InvalidMessageException("commands require operationId for result correlation");
    }
    if (envelope.idempotencyKey() == null || envelope.idempotencyKey().isBlank()) {
      throw new InvalidMessageException("commands require a non-blank idempotencyKey");
    }

    if ("academic".equals(envelope.producer())) {
      validateAcademicCommand(envelope);
    } else if (!(envelope.actor() instanceof Actor.Account account) || account.accountId() == null) {
      throw new InvalidMessageException("BFF commands require an ACCOUNT actor with accountId");
    }
  }

  private static void validateAcademicCommand(MessageEnvelope envelope) {
    if (!ACADEMIC_COMMANDS.contains(envelope.messageType())) {
      throw new InvalidMessageException("academic may only CreatePerson or UpdatePerson");
    }
    if (!(envelope.actor() instanceof Actor.System system) || !"academic".equals(system.service())) {
      throw new InvalidMessageException("academic commands require SYSTEM/academic actor");
    }
    JsonNode execution = envelope.payload().path("importExecution");
    requireUuid(execution, "importId");
    requireUuid(execution, "planItemId");
    requireUuid(execution, "authorizedByAccountId");
    if (execution.path("executionGeneration").asInt(-1) < 1) {
      throw new InvalidMessageException("importExecution.executionGeneration must be positive");
    }
    String expectedScope = MessageTypes.CREATE_PERSON.equals(envelope.messageType())
        ? "CREATE_PERSON"
        : "UPDATE_PERSON";
    if (!expectedScope.equals(execution.path("scope").asString(null))) {
      throw new InvalidMessageException("importExecution.scope does not match command");
    }
  }

  private static void requireNonNull(Object value, String fieldName) {
    if (value == null) {
      throw new InvalidMessageException(fieldName + " must not be null");
    }
  }

  private static void requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new InvalidMessageException(fieldName + " must not be blank");
    }
  }

  private static UUID requireUuid(JsonNode node, String field) {
    if (!node.hasNonNull(field)) {
      throw new InvalidMessageException(field + " must not be null");
    }
    try {
      return UUID.fromString(node.path(field).asString());
    } catch (IllegalArgumentException exception) {
      throw new InvalidMessageException(field + " must be a UUID");
    }
  }
}

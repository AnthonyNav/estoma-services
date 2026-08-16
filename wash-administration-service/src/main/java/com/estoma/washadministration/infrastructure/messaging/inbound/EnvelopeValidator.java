package com.estoma.washadministration.infrastructure.messaging.inbound;

import com.estoma.washadministration.contracts.Actor;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.MessageKind;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class EnvelopeValidator {

  private static final Set<String> KNOWN_QUERY_REQUESTERS =
      Set.of("bff", "appointments", "wash-execution", "academic");
  private static final Set<String> AUTHORIZATION_EVENTS =
      Set.of(
          "AccountRoleAssigned",
          "AccountRoleChanged",
          "SystemAccessGranted",
          "SystemAccessSuspended",
          "SystemAccessRevoked",
          "SystemAccessRestored");

  public void validateCommand(MessageEnvelope envelope) {
    validateCommon(envelope, MessageKind.COMMAND);
    if (!"bff".equals(envelope.producer())) {
      throw new InvalidMessageException("COMMAND_PRODUCER_NOT_ALLOWED");
    }
    Actor actor = requireActor(envelope.actor());
    if (!"ACCOUNT".equals(actor.type()) || actor.accountId() == null || actor.service() != null) {
      throw new InvalidMessageException("COMMAND_ACTOR_NOT_ALLOWED");
    }
    require(envelope.operationId(), "COMMAND_OPERATION_ID_REQUIRED");
    requireText(envelope.idempotencyKey(), "COMMAND_IDEMPOTENCY_KEY_REQUIRED");
  }

  public void validateConfigurationQuery(MessageEnvelope envelope) {
    validateCommon(envelope, MessageKind.QUERY);
    if (!KNOWN_QUERY_REQUESTERS.contains(envelope.producer())) {
      throw new InvalidMessageException("QUERY_PRODUCER_NOT_ALLOWED");
    }
    Actor actor = requireActor(envelope.actor());
    if (!"SYSTEM".equals(actor.type()) || !envelope.producer().equals(actor.service())) {
      throw new InvalidMessageException("QUERY_ACTOR_NOT_ALLOWED");
    }
  }

  public void validateAuthorizationEvent(MessageEnvelope envelope) {
    validateCommon(envelope, MessageKind.EVENT);
    if (!"authorization".equals(envelope.producer())
        || !AUTHORIZATION_EVENTS.contains(envelope.messageType())
        || envelope.aggregateId() == null
        || envelope.aggregateVersion() == null
        || envelope.aggregateVersion() <= 0) {
      throw new InvalidMessageException("AUTHORIZATION_EVENT_NOT_ALLOWED");
    }
    requireActor(envelope.actor());
  }

  public void validateAuthorizationResult(MessageEnvelope envelope) {
    validateCommon(envelope, MessageKind.RESULT);
    if (!"authorization".equals(envelope.producer())) {
      throw new InvalidMessageException("AUTHORIZATION_RESULT_NOT_ALLOWED");
    }
    if (!("GetAccountAuthorizationSnapshotResult".equals(envelope.messageType())
        || "ListAccountAuthorizationSnapshotsPageResult".equals(envelope.messageType()))) {
      throw new InvalidMessageException("AUTHORIZATION_RESULT_TYPE_NOT_ALLOWED");
    }
    require(envelope.causationId(), "AUTHORIZATION_RESULT_CAUSATION_REQUIRED");
    requireActor(envelope.actor());
  }

  private static void validateCommon(MessageEnvelope envelope, MessageKind expectedKind) {
    if (envelope == null
        || envelope.messageId() == null
        || envelope.messageType() == null
        || envelope.messageType().isBlank()
        || envelope.messageVersion() != 1
        || envelope.messageKind() != expectedKind
        || envelope.producer() == null
        || envelope.producer().isBlank()
        || envelope.occurredAt() == null
        || envelope.correlationId() == null
        || envelope.payload() == null
        || envelope.payload().isNull()) {
      throw new InvalidMessageException("INVALID_MESSAGE_ENVELOPE");
    }
  }

  private static Actor requireActor(Actor actor) {
    if (actor == null || actor.type() == null) {
      throw new InvalidMessageException("ACTOR_REQUIRED");
    }
    if ("ACCOUNT".equals(actor.type()) && actor.accountId() == null) {
      throw new InvalidMessageException("ACCOUNT_ACTOR_ID_REQUIRED");
    }
    if ("ACCOUNT".equals(actor.type()) && actor.service() != null) {
      throw new InvalidMessageException("ACCOUNT_ACTOR_SERVICE_NOT_ALLOWED");
    }
    if ("ACCOUNT".equals(actor.type())) {
      return actor;
    }
    if ("SYSTEM".equals(actor.type())) {
      if (actor.service() == null || actor.service().isBlank()) {
        throw new InvalidMessageException("SYSTEM_ACTOR_SERVICE_REQUIRED");
      }
      if (actor.accountId() != null) {
        throw new InvalidMessageException("SYSTEM_ACTOR_ACCOUNT_ID_NOT_ALLOWED");
      }
      return actor;
    }
    throw new InvalidMessageException("ACTOR_TYPE_NOT_ALLOWED");
  }

  private static <T> T require(T value, String code) {
    if (value == null) {
      throw new InvalidMessageException(code);
    }
    return value;
  }

  private static String requireText(String value, String code) {
    if (value == null || value.isBlank()) {
      throw new InvalidMessageException(code);
    }
    return value;
  }
}

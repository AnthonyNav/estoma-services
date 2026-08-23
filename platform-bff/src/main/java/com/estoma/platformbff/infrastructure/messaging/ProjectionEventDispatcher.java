package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.security.RevocationProjectionEvent;
import com.estoma.platformbff.application.security.RevocationProjectionEvent.AccountSessionsRevoked;
import com.estoma.platformbff.application.security.RevocationProjectionEvent.CutoffKind;
import com.estoma.platformbff.application.security.RevocationProjectionEvent.Metadata;
import com.estoma.platformbff.application.security.RevocationProjectionService;
import com.estoma.platformbff.contracts.messaging.EventEnvelope;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ProjectionEventDispatcher {
  public enum DispatchOutcome { APPLIED, UNSUPPORTED, UNTRUSTED }

  private static final Set<String> AUTHENTICATION_TYPES = Set.of(
      "AccountCreated",
      "SessionRevoked",
      "AllAccountSessionsRevoked",
      "OtherAccountSessionsRevoked",
      "AccountDeactivated");

  private final RevocationProjectionService revocations;

  public ProjectionEventDispatcher(RevocationProjectionService revocations) {
    this.revocations = revocations;
  }

  public DispatchOutcome dispatch(EventEnvelope event) {
    RevocationProjectionEvent mapped;
    try {
      mapped = map(event);
    } catch (IllegalArgumentException invalidContract) {
      return DispatchOutcome.UNTRUSTED;
    }
    if (mapped == null) return DispatchOutcome.UNSUPPORTED;
    revocations.apply(mapped);
    return DispatchOutcome.APPLIED;
  }

  private static RevocationProjectionEvent map(EventEnvelope event) {
    validateEnvelope(event);
    String type = event.messageType();
    if (!AUTHENTICATION_TYPES.contains(type) && !"PersonDeactivated".equals(type)) {
      return null;
    }
    if (AUTHENTICATION_TYPES.contains(type) && !"authentication".equals(event.producer())) {
      throw new IllegalArgumentException("authentication event producer mismatch");
    }
    if ("PersonDeactivated".equals(type) && !"users".equals(event.producer())) {
      throw new IllegalArgumentException("person event producer mismatch");
    }

    JsonNode payload = event.payload();
    Metadata metadata = new Metadata(
        event.messageId(), event.messageType(), event.messageVersion(), event.producer(),
        event.occurredAt(), event.correlationId(), event.causationId(),
        event.aggregateId(), event.aggregateVersion());

    return switch (type) {
      case "AccountCreated" -> {
        UUID accountId = uuid(payload, "accountId");
        UUID personId = uuid(payload, "personId");
        assertAggregate(event, accountId);
        yield new RevocationProjectionEvent.AccountCreated(
            metadata, accountId, personId, positiveVersion(event), instant(payload, "createdAt"));
      }
      case "SessionRevoked" -> new RevocationProjectionEvent.SessionRevoked(
          metadata,
          uuid(payload, "sessionId"),
          instant(payload, "revokedAt").getEpochSecond(),
          text(payload, "reasonCode"));
      case "AllAccountSessionsRevoked" -> {
        UUID accountId = uuid(payload, "accountId");
        assertAggregateIfPresent(event, accountId);
        yield new AccountSessionsRevoked(
            metadata, accountId, instant(payload, "revokedAt").getEpochSecond(),
            CutoffKind.ALL, null, text(payload, "reasonCode"));
      }
      case "OtherAccountSessionsRevoked" -> {
        UUID accountId = uuid(payload, "accountId");
        UUID currentSessionId = uuid(payload, "currentSessionId");
        assertAggregateIfPresent(event, accountId);
        yield new AccountSessionsRevoked(
            metadata, accountId, instant(payload, "revokedAt").getEpochSecond(),
            CutoffKind.OTHER, currentSessionId, text(payload, "reasonCode"));
      }
      case "AccountDeactivated" -> {
        UUID accountId = uuid(payload, "accountId");
        UUID personId = uuid(payload, "personId");
        Instant deactivatedAt = instant(payload, "deactivatedAt");
        assertAggregate(event, accountId);
        yield new RevocationProjectionEvent.AccountDeactivated(
            metadata, accountId, personId, positiveVersion(event),
            deactivatedAt.getEpochSecond(), text(payload, "reasonCode"), deactivatedAt);
      }
      case "PersonDeactivated" -> {
        UUID personId = uuid(payload, "personId");
        assertAggregate(event, personId);
        yield new RevocationProjectionEvent.PersonDeactivated(
            metadata, personId, instant(payload, "deactivatedAt").getEpochSecond(),
            text(payload, "reasonCode"));
      }
      default -> null;
    };
  }

  private static void validateEnvelope(EventEnvelope event) {
    if (event == null
        || event.messageId() == null
        || event.messageKind() != MessageKind.EVENT
        || event.messageType() == null || event.messageType().isBlank()
        || event.messageVersion() != 1
        || event.producer() == null || event.producer().isBlank()
        || event.occurredAt() == null
        || event.payload() == null || !event.payload().isObject()) {
      throw new IllegalArgumentException("invalid projection event envelope");
    }
  }

  private static void assertAggregate(EventEnvelope event, UUID expected) {
    if (event.aggregateId() == null || !expected.equals(event.aggregateId())) {
      throw new IllegalArgumentException("event aggregateId mismatch");
    }
  }

  private static void assertAggregateIfPresent(EventEnvelope event, UUID expected) {
    if (event.aggregateId() != null && !expected.equals(event.aggregateId())) {
      throw new IllegalArgumentException("event aggregateId mismatch");
    }
  }

  private static int positiveVersion(EventEnvelope event) {
    if (event.aggregateVersion() == null || event.aggregateVersion() <= 0) {
      throw new IllegalArgumentException("event aggregateVersion is required");
    }
    return event.aggregateVersion();
  }

  private static UUID uuid(JsonNode node, String field) {
    try {
      return UUID.fromString(text(node, field));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid UUID field: " + field, invalid);
    }
  }

  private static Instant instant(JsonNode node, String field) {
    try {
      return Instant.parse(text(node, field));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid timestamp field: " + field, invalid);
    }
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field) || !node.get(field).isTextual()
        || node.get(field).asText().isBlank()) {
      throw new IllegalArgumentException("missing textual field: " + field);
    }
    return node.get(field).asText();
  }
}

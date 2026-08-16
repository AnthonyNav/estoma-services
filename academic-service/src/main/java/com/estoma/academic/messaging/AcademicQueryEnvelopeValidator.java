package com.estoma.academic.messaging;

import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Validates the recovery Queries owned by Academic before any database read. */
@Component
public class AcademicQueryEnvelopeValidator {
  private static final Set<String> QUERY_TYPES = Set.of(
      "GetStudentAcademicEligibilitySnapshot",
      "ListStudentAcademicEligibilitySnapshotsPage");
  private static final String PRACTICAS = "PRACTICAS_PROFESIONALES";

  public void validate(MessageEnvelope envelope) {
    required(envelope, "envelope");
    required(envelope.messageId(), "messageId");
    required(envelope.occurredAt(), "occurredAt");
    required(envelope.correlationId(), "correlationId");
    required(envelope.operationId(), "operationId");
    required(envelope.payload(), "payload");
    requiredText(envelope.messageType(), "messageType");
    requiredText(envelope.producer(), "producer");

    if (!"QUERY".equals(envelope.messageKind())) {
      throw invalid("expected messageKind=QUERY");
    }
    if (envelope.messageVersion() != 1) {
      throw invalid("unsupported messageVersion");
    }
    if (!"authorization".equals(envelope.producer())) {
      throw invalid("QUERY_PRODUCER_NOT_ALLOWED");
    }
    if (!(envelope.actor() instanceof Actor actor)
        || !"SYSTEM".equals(actor.type())
        || !"authorization".equals(actor.service())
        || actor.accountId() != null) {
      throw invalid("QUERY_ACTOR_NOT_ALLOWED");
    }
    if (!QUERY_TYPES.contains(envelope.messageType())) {
      throw invalid("unknown Academic Query");
    }
    if (!envelope.payload().isObject()) {
      throw invalid("payload must be an object");
    }

    if ("GetStudentAcademicEligibilitySnapshot".equals(envelope.messageType())) {
      validateGet(envelope.payload());
    } else {
      validateList(envelope.payload());
    }
  }

  private static void validateGet(JsonNode payload) {
    boolean hasProfile = uuid(payload, "studentAcademicProfileId", "INVALID_ELIGIBILITY_TARGET") != null;
    boolean hasAccount = uuid(payload, "accountId", "INVALID_ELIGIBILITY_TARGET") != null;
    if (hasProfile == hasAccount) {
      throw invalid("INVALID_ELIGIBILITY_TARGET");
    }
    requireSystem(payload);
  }

  private static void validateList(JsonNode payload) {
    if (payload.hasNonNull("cursor")) {
      uuid(payload, "cursor", "INVALID_CURSOR");
    }
    int limit = payload.path("limit").asInt(-1);
    if (limit < 1 || limit > 500) {
      throw invalid("INVALID_LIMIT");
    }
    if (payload.hasNonNull("systemCode") && !PRACTICAS.equals(payload.path("systemCode").asString())) {
      throw invalid("INVALID_SYSTEM_CODE");
    }
  }

  private static void requireSystem(JsonNode payload) {
    if (!PRACTICAS.equals(payload.path("systemCode").asString())) {
      throw invalid("INVALID_SYSTEM_CODE");
    }
  }

  private static java.util.UUID uuid(JsonNode payload, String field, String errorCode) {
    if (!payload.hasNonNull(field)) {
      return null;
    }
    try {
      return java.util.UUID.fromString(payload.path(field).asString());
    } catch (IllegalArgumentException ex) {
      throw invalid(errorCode);
    }
  }

  private static void required(Object value, String field) {
    if (value == null) {
      throw invalid(field + " must not be null");
    }
  }

  private static void requiredText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw invalid(field + " must not be blank");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}

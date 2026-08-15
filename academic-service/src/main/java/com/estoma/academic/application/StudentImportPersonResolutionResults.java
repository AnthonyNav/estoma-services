package com.estoma.academic.application;

import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.messaging.EnvelopeCodec;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Durable fixture boundary for authoritative Users Person-resolution outcomes. */
@Component
public class StudentImportPersonResolutionResults {
  private static final String WORK_TYPE = "AUTHORITATIVE_PERSON_RESOLUTION";

  private final JdbcClient jdbc;
  private final EnvelopeCodec codec;
  private final StudentImportPlanBuilder planBuilder;
  private final StudentImportValidationRejectionService rejectionService;

  public StudentImportPersonResolutionResults(
      JdbcClient jdbc,
      EnvelopeCodec codec,
      StudentImportPlanBuilder planBuilder,
      StudentImportValidationRejectionService rejectionService) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.planBuilder = planBuilder;
    this.rejectionService = rejectionService;
  }

  /** Applies one Users result to the logical query work identified by correlation and operation id. */
  @Transactional
  public boolean apply(MessageEnvelope envelope) {
    validateResultEnvelope(envelope);
    Instant now = Instant.now();
    if (jdbc.sql("""
        INSERT INTO processed_messages (
          consumer_name,message_id,correlation_id,message_type,message_kind,producer,
          aggregate_id,aggregate_version,envelope,processing_state,reconciliation_state,processed_at)
        VALUES ('academic.results',:id,:correlation,:type,:kind,:producer,:aggregate,:version,
          CAST(:envelope AS jsonb),'APPLIED',NULL,:now)
        ON CONFLICT DO NOTHING
        """).param("id", envelope.messageId()).param("correlation", envelope.correlationId())
        .param("type", envelope.messageType()).param("kind", envelope.messageKind()).param("producer", envelope.producer())
        .param("aggregate", envelope.aggregateId()).param("version", envelope.aggregateVersion())
        .param("envelope", codec.json(envelope)).param("now", java.sql.Timestamp.from(now)).update() == 0) {
      return false;
    }

    Work work = jdbc.sql("""
        SELECT import_id,work_key,current_operation_id,status::text
        FROM student_import_validation_work
        WHERE work_type=:type AND import_id=:import AND current_operation_id=:operation
        FOR UPDATE
        """).param("type", WORK_TYPE).param("import", envelope.correlationId())
        .param("operation", envelope.operationId())
        .query((rs, n) -> new Work(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4)))
        .optional().orElse(null);
    if (work == null || !knownQueryAttempt(envelope, work)) return true;

    PersonResolution resolution = resolution(work.email, envelope.payload().path("data"));
    if (!"WAITING_RESULT".equals(work.status)) return true;
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("outcome", resolution.outcome().name());
    result.put("sourceResultMessageId", envelope.messageId());
    if (resolution.personId() != null) result.put("personId", resolution.personId());
    if (resolution.status() != null) result.put("status", resolution.status());
    int changed = jdbc.sql("""
        UPDATE student_import_validation_work
        SET status='SUCCEEDED',result=CAST(:result AS jsonb),next_attempt_at=NULL,
            last_error_code=NULL,last_error_at=NULL,updated_at=:now
        WHERE import_id=:importId AND work_type=:type AND work_key=:email
          AND current_operation_id=:operation AND status='WAITING_RESULT'
        """).param("result", codec.json(result))
        .param("now", java.sql.Timestamp.from(now)).param("importId", work.importId).param("type", WORK_TYPE)
        .param("email", work.email).param("operation", work.operationId).update();
    if (changed != 1) return true;
    if (resolution.outcome() == Outcome.INACTIVE_MATCH) {
      rejectionService.rejectIdentityConflict(
          work.importId,
          "An imported institutional email resolves to an inactive Person");
      return true;
    }
    planBuilder.buildIfReady(work.importId);
    return true;
  }

  private boolean knownQueryAttempt(MessageEnvelope envelope, Work work) {
    return jdbc.sql("""
        SELECT EXISTS (
          SELECT 1 FROM outbox_messages
          WHERE message_id=:causation AND message_kind='QUERY'
            AND message_type='ResolvePersonByInstitutionalEmail'
            AND correlation_id=:import
            AND payload->>'institutionalEmail'=:email
            AND metadata->>'operationId'=:operation)
        """).param("causation", envelope.causationId()).param("import", work.importId)
        .param("email", work.email).param("operation", work.operationId.toString())
        .query(Boolean.class).single();
  }

  private static void validate(PersonResolution resolution, String email) {
    if (resolution.outcome() == null) throw new IllegalArgumentException("resolution outcome is required");
    switch (resolution.outcome()) {
      case FOUND -> {
        if (resolution.personId() == null || !"ACTIVE".equals(resolution.status())) throw new IllegalArgumentException("FOUND must identify an active Person");
      }
      case INACTIVE_MATCH -> {
        if (resolution.personId() == null || !"INACTIVE".equals(resolution.status())) throw new IllegalArgumentException("INACTIVE_MATCH must identify an inactive Person");
      }
      case NOT_FOUND -> {
        if (resolution.personId() != null || resolution.status() != null) throw new IllegalArgumentException("NOT_FOUND must not disclose a Person");
      }
    }
    if (email.isBlank()) throw new IllegalArgumentException("institutional email is required");
  }

  private static void validateResultEnvelope(MessageEnvelope envelope) {
    if (envelope == null || envelope.messageId() == null || envelope.correlationId() == null
        || envelope.causationId() == null || envelope.operationId() == null
        || !"ResolvePersonByInstitutionalEmailResult".equals(envelope.messageType())
        || !"RESULT".equals(envelope.messageKind()) || !"users".equals(envelope.producer())
        || envelope.payload() == null || !envelope.payload().isObject()
        || !"SUCCEEDED".equals(envelope.payload().path("status").asString())
        || !envelope.payload().path("data").isObject()) {
      throw new IllegalArgumentException("invalid ResolvePersonByInstitutionalEmailResult envelope");
    }
  }

  private static PersonResolution resolution(String email, JsonNode data) {
    Outcome outcome;
    try {
      outcome = Outcome.valueOf(data.path("outcome").asString());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("invalid person-resolution outcome", ex);
    }
    UUID personId;
    try {
      personId = data.hasNonNull("personId") ? UUID.fromString(data.path("personId").asString()) : null;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("invalid person-resolution personId", ex);
    }
    PersonResolution resolution = new PersonResolution(email, outcome, personId,
        data.hasNonNull("status") ? data.path("status").asString() : null);
    validate(resolution, email);
    return resolution;
  }

  private enum Outcome { FOUND, NOT_FOUND, INACTIVE_MATCH }
  private record PersonResolution(String institutionalEmail, Outcome outcome, UUID personId, String status) {}
  private record Work(UUID importId, String email, UUID operationId, String status) {}
}

package com.estoma.academic.application;

import com.estoma.academic.messaging.EnvelopeCodec;
import com.estoma.academic.persistence.OutboxRepository;
import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
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
  private final OutboxRepository outbox;

  public StudentImportPersonResolutionResults(
      JdbcClient jdbc, EnvelopeCodec codec, StudentImportPlanBuilder planBuilder, OutboxRepository outbox) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.planBuilder = planBuilder;
    this.outbox = outbox;
  }

  /** Applies one Users result to the exact query work identified by its causation id. */
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
        WHERE work_type=:type AND current_message_id=:causation
        FOR UPDATE
        """).param("type", WORK_TYPE).param("causation", envelope.causationId())
        .query((rs, n) -> new Work(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4)))
        .optional().orElse(null);
    if (work == null || !work.importId.equals(envelope.correlationId()) || !work.operationId.equals(envelope.operationId())) {
      return true; // A late result is durably observed but cannot alter another import's work.
    }
    PersonResolution resolution = resolution(work.email, envelope.payload().path("data"));
    if (!"WAITING_RESULT".equals(work.status)) return true;
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("outcome", resolution.outcome().name());
    if (resolution.personId() != null) result.put("personId", resolution.personId());
    if (resolution.status() != null) result.put("status", resolution.status());
    int changed = jdbc.sql("""
        UPDATE student_import_validation_work
        SET status='SUCCEEDED',result=CAST(:result AS jsonb),next_attempt_at=NULL,
            last_error_code=NULL,last_error_at=NULL,updated_at=:now
        WHERE import_id=:importId AND work_type=:type AND work_key=:email
          AND current_message_id=:causation AND status='WAITING_RESULT'
        """).param("result", codec.json(result))
        .param("now", java.sql.Timestamp.from(now)).param("importId", work.importId).param("type", WORK_TYPE)
        .param("email", work.email).param("causation", envelope.causationId()).update();
    if (changed != 1) return true;
    if (resolution.outcome() == Outcome.INACTIVE_MATCH) {
      rejectIdentityConflict(work.importId);
      return true;
    }
    planBuilder.buildIfReady(work.importId);
    return true;
  }

  private void rejectIdentityConflict(UUID importId) {
    Instant now = Instant.now();
    Process process = jdbc.sql("""
        SELECT academic_period_id,process_version FROM student_import_processes
        WHERE import_id=:id AND status='VALIDATING' FOR UPDATE
        """).param("id", importId).query((rs, n) -> new Process(rs.getObject(1, UUID.class), rs.getInt(2))).optional()
        .orElseThrow(() -> new IllegalStateException("student import is not validating"));
    int version = process.version + 1;
    jdbc.sql("""
        INSERT INTO student_import_validation_errors (import_id,sequence,error_code,message,created_at)
        VALUES (:id,1,'IDENTITY_CONFLICT','An imported institutional email resolves to an inactive Person',:now)
        ON CONFLICT (import_id,sequence) DO NOTHING
        """).param("id", importId).param("now", java.sql.Timestamp.from(now)).update();
    jdbc.sql("""
        UPDATE student_import_processes SET status='REJECTED',process_version=:version,failed_step='VALIDATION',
          failure_code='IDENTITY_CONFLICT',failure_reason='Institutional email identity conflict',recoverable=false,
          failed_at=:now,updated_at=:now WHERE import_id=:id AND status='VALIDATING' AND process_version=:expected
        """).param("id", importId).param("version", version).param("expected", process.version).param("now", java.sql.Timestamp.from(now)).update();
    outbox.append(UUID.randomUUID(), "StudentImportRejected", "EVENT", importId, version, importId, null,
        codec.json(Map.of("importId", importId, "academicPeriodId", process.periodId, "errorCount", 1, "rejectedAt", now)),
        codec.json(Map.of("actor", new Actor("SYSTEM", null, "academic"))), now);
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
        || !"RESULT".equals(envelope.messageKind()) || !"users-service".equals(envelope.producer())
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
  private record Process(UUID periodId, int version) {}
}

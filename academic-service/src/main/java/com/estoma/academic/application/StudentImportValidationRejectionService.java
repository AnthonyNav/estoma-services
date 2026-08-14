package com.estoma.academic.application;

import com.estoma.academic.contract.Actor;
import com.estoma.academic.messaging.EnvelopeCodec;
import com.estoma.academic.persistence.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Applies one contract-consistent terminal validation rejection, including period readiness. */
@Component
public class StudentImportValidationRejectionService {
  private static final Actor SYSTEM_ACTOR = new Actor("SYSTEM", null, "academic");

  private final JdbcClient jdbc;
  private final EnvelopeCodec codec;
  private final OutboxRepository outbox;

  public StudentImportValidationRejectionService(JdbcClient jdbc, EnvelopeCodec codec, OutboxRepository outbox) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.outbox = outbox;
  }

  @Transactional
  public boolean rejectIdentityConflict(UUID importId, String message) {
    return reject(importId, "IDENTITY_CONFLICT", message, Instant.now());
  }

  @Transactional
  public boolean reject(UUID importId, String errorCode, String message, Instant now) {
    Process process = jdbc.sql("""
        SELECT p.academic_period_id,p.process_version,period.academic_data_status::text,period.version
        FROM student_import_processes p
        JOIN academic_periods period ON period.academic_period_id=p.academic_period_id
        WHERE p.import_id=:id
        FOR UPDATE OF p,period
        """)
        .param("id", importId)
        .query((rs, n) -> new Process(
            rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getInt(4)))
        .optional().orElse(null);
    if (process == null || !isValidating(importId)) return false;

    int sequence = jdbc.sql("SELECT COALESCE(max(sequence),0)+1 FROM student_import_validation_errors WHERE import_id=:id")
        .param("id", importId).query(Integer.class).single();
    jdbc.sql("""
        INSERT INTO student_import_validation_errors
          (import_id,sequence,error_code,message,created_at)
        VALUES (:id,:sequence,:code,:message,:now)
        """)
        .param("id", importId).param("sequence", sequence).param("code", errorCode)
        .param("message", message).param("now", java.sql.Timestamp.from(now)).update();

    int version = process.processVersion + 1;
    int changed = jdbc.sql("""
        UPDATE student_import_processes
        SET status='REJECTED',process_version=:version,failed_step='VALIDATION',failure_code=:code,
            failure_reason=:reason,recoverable=false,failed_at=:now,updated_at=:now
        WHERE import_id=:id AND status='VALIDATING' AND process_version=:expected
        """)
        .param("version", version).param("code", errorCode).param("reason", message)
        .param("now", java.sql.Timestamp.from(now)).param("id", importId)
        .param("expected", process.processVersion).update();
    if (changed != 1) throw new IllegalStateException("student import changed while rejecting validation");

    int errorCount = jdbc.sql("SELECT count(*) FROM student_import_validation_errors WHERE import_id=:id")
        .param("id", importId).query(Integer.class).single();
    append("StudentImportRejected", importId, version, Map.of(
        "importId", importId,
        "academicPeriodId", process.periodId,
        "errorCount", errorCount,
        "rejectedAt", now), now);
    append("StudentImportValidationErrorsAvailable", importId, version, Map.of(
        "importId", importId,
        "academicPeriodId", process.periodId,
        "batchNumber", 1,
        "batchCount", 1,
        "errorCount", 1,
        "errors", List.of(Map.of(
            "sequence", sequence,
            "errorCode", errorCode,
            "message", message)),
        "availableAt", now), now);

    if ("PENDING_IMPORT".equals(process.periodDataStatus)) {
      int periodChanged = jdbc.sql("""
          UPDATE academic_periods
          SET academic_data_status='IMPORT_FAILED',version=version+1,updated_at=:now
          WHERE academic_period_id=:id AND academic_data_status='PENDING_IMPORT' AND version=:expected
          """)
          .param("id", process.periodId).param("expected", process.periodVersion)
          .param("now", java.sql.Timestamp.from(now)).update();
      if (periodChanged != 1) throw new IllegalStateException("academic period changed while rejecting validation");
      append("AcademicPeriodDataStatusChanged", process.periodId, process.periodVersion + 1, Map.of(
          "academicPeriodId", process.periodId,
          "previousAcademicDataStatus", "PENDING_IMPORT",
          "academicDataStatus", "IMPORT_FAILED",
          "reason", "IMPORT_VALIDATION_REJECTED",
          "changedAt", now), now);
    }
    return true;
  }

  private boolean isValidating(UUID importId) {
    return jdbc.sql("SELECT status::text='VALIDATING' FROM student_import_processes WHERE import_id=:id")
        .param("id", importId).query(Boolean.class).single();
  }

  private void append(String type, UUID aggregateId, int version, Map<String, Object> payload, Instant now) {
    outbox.append(UUID.randomUUID(), type, "EVENT", aggregateId, version, aggregateId, null,
        codec.json(payload), codec.json(Map.of("actor", SYSTEM_ACTOR)), now);
  }

  private record Process(UUID periodId, int processVersion, String periodDataStatus, int periodVersion) {}
}

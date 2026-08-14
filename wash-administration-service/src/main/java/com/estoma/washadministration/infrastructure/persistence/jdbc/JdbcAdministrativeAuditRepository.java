package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.AdministrativeAuditRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdministrativeAuditRepository implements AdministrativeAuditRepository {

  private final JdbcClient jdbc;

  JdbcAdministrativeAuditRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(AuditRecord record) {
    jdbc.sql(
            """
            INSERT INTO administrative_audit_records (
              audit_id, operation_id, correlation_id, operation_type, aggregate_id, account_id, reason, recorded_at
            ) VALUES (
              :auditId, :operationId, :correlationId, :operationType, :aggregateId, :accountId, :reason, :recordedAt
            )
            """)
        .param("auditId", record.auditId())
        .param("operationId", record.operationId())
        .param("correlationId", record.correlationId())
        .param("operationType", record.operationType())
        .param("aggregateId", record.aggregateId())
        .param("accountId", record.accountId())
        .param("reason", record.reason())
        .param("recordedAt", Timestamps.toTimestamp(record.recordedAt()))
        .update();
  }
}

package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.AuthorizationAuditRepository;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAuthorizationAuditRepository implements AuthorizationAuditRepository {

  private final JdbcClient jdbc;

  JdbcAuthorizationAuditRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(AuditEntry entry) {
    jdbc.sql(
            """
            INSERT INTO authorization_audit_entries (
              audit_id, source_message_id, source_producer, aggregate_type, aggregate_id, aggregate_version,
              action, actor_type, actor_account_id, actor_service, business_initiator_account_id, business_import_id,
              correlation_id, causation_id, operation_id, idempotency_key, result, reason_code, reason_detail, occurred_at
            ) VALUES (
              :auditId, :sourceMessageId, :sourceProducer, :aggregateType, :aggregateId, :aggregateVersion,
              :action, CAST(:actorType AS actor_type), :actorAccountId, :actorService, :businessInitiatorAccountId, :businessImportId,
              :correlationId, :causationId, :operationId, :idempotencyKey, :result, :reasonCode, :reasonDetail, :occurredAt
            )
            """)
        .param("auditId", entry.auditId())
        .param("sourceMessageId", entry.sourceMessageId())
        .param("sourceProducer", entry.sourceProducer())
        .param("aggregateType", entry.aggregateType())
        .param("aggregateId", entry.aggregateId())
        .param("aggregateVersion", entry.aggregateVersion())
        .param("action", entry.action())
        .param("actorType", entry.actor().type().name())
        .param("actorAccountId", entry.actor().accountId())
        .param("actorService", entry.actor().service())
        .param("businessInitiatorAccountId", entry.businessInitiatorAccountId())
        .param("businessImportId", entry.businessImportId())
        .param("correlationId", entry.correlationId())
        .param("causationId", entry.causationId())
        .param("operationId", entry.operationId())
        .param("idempotencyKey", entry.idempotencyKey())
        .param("result", entry.result())
        .param("reasonCode", entry.reasonCode())
        .param("reasonDetail", entry.reasonDetail())
        .param("occurredAt", Timestamps.toTimestamp(entry.occurredAt()))
        .update();
  }
}

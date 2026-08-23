package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.OutboxRepository;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {
  private final JdbcTemplate jdbc;

  public JdbcOutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public void append(OutboxMessage m) {
    jdbc.update("""
        INSERT INTO outbox_messages(
          message_id,message_type,message_version,message_kind,producer,routing_key,occurred_at,
          operation_id,idempotency_key,aggregate_id,aggregate_version,correlation_id,causation_id,
          actor_json,payload_json,status,attempt_count,available_at,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),'PENDING',0,?,?)
        """, m.messageId(), m.messageType(), m.messageVersion(), m.messageKind(), m.producer(),
        m.routingKey(), m.occurredAt(), m.operationId(), m.idempotencyKey(), m.aggregateId(),
        m.aggregateVersion(), m.correlationId(), m.causationId(), m.actorJson(), m.payloadJson(),
        m.occurredAt(), Instant.now());
  }
}

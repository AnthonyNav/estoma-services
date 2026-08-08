package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.application.port.OutboxPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of the Transactional Outbox write side. Only {@code
 * append} in this tramo: the read/claim/publish side (the relay) is a
 * messaging-tramo concern, not part of this port.
 */
@Repository
public class JdbcOutboxRepository implements OutboxPort {

  private final JdbcClient jdbc;

  JdbcOutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(OutboxAppendRequest request) {
    jdbc.sql(
            """
            INSERT INTO outbox_messages (
              message_id, message_type, message_version, message_kind, aggregate_id, aggregate_version,
              correlation_id, causation_id, payload, status, attempts, occurred_at, available_at
            ) VALUES (
              :messageId, :messageType, :messageVersion, :messageKind, :aggregateId, :aggregateVersion,
              :correlationId, :causationId, CAST(:payload AS jsonb),
              CAST('PENDING' AS outbox_status), 0, :occurredAt, :occurredAt
            )
            """)
        .param("messageId", request.messageId())
        .param("messageType", request.messageType())
        .param("messageVersion", request.messageVersion())
        .param("messageKind", request.messageKind())
        .param("aggregateId", request.aggregateId())
        .param("aggregateVersion", request.aggregateVersion())
        .param("correlationId", request.correlationId())
        .param("causationId", request.causationId())
        .param("payload", request.payloadJson())
        .param("occurredAt", Timestamps.toTimestamp(request.occurredAt()))
        .update();
  }
}

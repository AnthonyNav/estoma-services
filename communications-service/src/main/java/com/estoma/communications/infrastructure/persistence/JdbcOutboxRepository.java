package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.OutboxAppendRequest;
import com.estoma.communications.application.port.OutboxPort;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC implementation of the Transactional Outbox write side. */
@Repository
public class JdbcOutboxRepository implements OutboxPort {

  private final JdbcClient jdbc;
  private final EnvelopeJsonCodec codec;

  JdbcOutboxRepository(JdbcClient jdbc, EnvelopeJsonCodec codec) {
    this.jdbc = jdbc;
    this.codec = codec;
  }

  @Override
  public void append(OutboxAppendRequest request) {
    jdbc.sql(
            """
            INSERT INTO outbox_messages (
              message_id, message_type, message_version, message_kind, aggregate_id, aggregate_version,
              correlation_id, causation_id, payload, status, attempts, occurred_at, available_at,
              operation_id, idempotency_key, actor, destination
            ) VALUES (
              :messageId, :messageType, :messageVersion, :messageKind, :aggregateId, :aggregateVersion,
              :correlationId, :causationId, CAST(:payload AS jsonb),
              CAST('PENDING' AS outbox_status), 0, :occurredAt, :occurredAt,
              :operationId, :idempotencyKey, CAST(:actor AS jsonb), :destination
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
        .param("operationId", request.operationId())
        .param("idempotencyKey", request.idempotencyKey())
        .param("actor", request.actor() == null ? null : codec.writeValueAsString(request.actor()))
        .param("destination", request.destination())
        .update();
  }
}

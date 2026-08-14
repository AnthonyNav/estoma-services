package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.OutboxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOutboxRepository implements OutboxRepository {

  private final JdbcClient jdbc;

  JdbcOutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(OutboxMessage message) {
    jdbc.sql(
            """
            INSERT INTO outbox_messages (
              message_id, message_type, message_version, message_kind, producer, occurred_at,
              routing_key,
              operation_id, idempotency_key, aggregate_id, aggregate_version, correlation_id,
              causation_id, actor, payload, metadata, status, attempts, available_at
            ) VALUES (
              :messageId, :messageType, :messageVersion, :messageKind, :producer, :occurredAt,
              :logicalDestination,
              :operationId, :idempotencyKey, :aggregateId, :aggregateVersion, :correlationId,
              :causationId, CAST(:actor AS jsonb), CAST(:payload AS jsonb), CAST(:metadata AS jsonb),
              'PENDING', 0, :availableAt
            )
            """)
        .param("messageId", message.messageId())
        .param("messageType", message.messageType())
        .param("messageVersion", message.messageVersion())
        .param("messageKind", message.messageKind())
        .param("producer", message.producer())
        // PR1 created the physical column as routing_key. Until PR3 owns transport mapping,
        // it stores only the transport-neutral logical destination token from Application.
        .param("logicalDestination", message.logicalDestination())
        .param("occurredAt", Timestamps.toTimestamp(message.occurredAt()))
        .param("operationId", message.operationId())
        .param("idempotencyKey", message.idempotencyKey())
        .param("aggregateId", message.aggregateId())
        .param("aggregateVersion", message.aggregateVersion())
        .param("correlationId", message.correlationId())
        .param("causationId", message.causationId())
        .param("actor", message.actorJson())
        .param("payload", message.payloadJson())
        .param("metadata", message.metadataJson())
        .param("availableAt", Timestamps.toTimestamp(message.occurredAt()))
        .update();
  }
}

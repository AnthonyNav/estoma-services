package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.contracts.Actor;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Repository
public class JdbcOutboxRelayRepository {

  public record OutboxRow(
      UUID messageId, String messageType, int messageVersion, String messageKind,
      UUID aggregateId, Integer aggregateVersion, UUID correlationId, UUID causationId,
      UUID operationId, UUID idempotencyKey, Actor actor, String destination,
      JsonNode payload, Instant occurredAt, int attempts) {}

  private final JdbcClient jdbc;
  private final EnvelopeJsonCodec codec;

  JdbcOutboxRelayRepository(JdbcClient jdbc, EnvelopeJsonCodec codec) {
    this.jdbc = jdbc;
    this.codec = codec;
  }

  @Transactional
  public List<OutboxRow> claim(int limit, Instant now) {
    return jdbc.sql("""
        WITH candidates AS (
          SELECT message_id FROM outbox_messages
          WHERE status IN ('PENDING','PROCESSING') AND available_at <= :now
          ORDER BY occurred_at
          LIMIT :limit
          FOR UPDATE SKIP LOCKED
        )
        UPDATE outbox_messages o
        SET status='PROCESSING', attempts=o.attempts+1,
            available_at=:leaseUntil, last_error=NULL
        FROM candidates c
        WHERE o.message_id=c.message_id
        RETURNING o.message_id,o.message_type,o.message_version,o.message_kind,
                  o.aggregate_id,o.aggregate_version,o.correlation_id,o.causation_id,
                  o.operation_id,o.idempotency_key,o.actor::text,o.destination,
                  o.payload::text,o.occurred_at,o.attempts
        """)
        .param("now", java.sql.Timestamp.from(now))
        .param("leaseUntil", java.sql.Timestamp.from(now.plusSeconds(60)))
        .param("limit", limit)
        .query((rs, n) -> new OutboxRow(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4),
            rs.getObject(5, UUID.class), nullableInt(rs, 6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class),
            rs.getObject(9, UUID.class), rs.getObject(10, UUID.class),
            actor(rs.getString(11)), rs.getString(12), codec.toJsonNode(codecNode(rs.getString(13))),
            rs.getTimestamp(14).toInstant(), rs.getInt(15)))
        .list();
  }

  public void markPublished(UUID messageId, Instant now) {
    jdbc.sql("UPDATE outbox_messages SET status='PUBLISHED',published_at=:now,last_error=NULL WHERE message_id=:id AND status='PROCESSING'")
        .param("id", messageId).param("now", java.sql.Timestamp.from(now)).update();
  }

  public void markRetry(UUID messageId, int attempts, Instant now) {
    boolean terminal = attempts >= 10;
    long backoffSeconds = Math.min(300, 1L << Math.min(8, Math.max(0, attempts - 1)));
    jdbc.sql("""
        UPDATE outbox_messages
        SET status=CAST(:status AS outbox_status), available_at=:availableAt,
            last_error=:error
        WHERE message_id=:id AND status='PROCESSING'
        """)
        .param("status", terminal ? "FAILED" : "PENDING")
        .param("availableAt", java.sql.Timestamp.from(now.plusSeconds(terminal ? 0 : backoffSeconds)))
        .param("error", "RABBIT_PUBLICATION_NOT_CONFIRMED")
        .param("id", messageId).update();
  }

  private Actor actor(String json) {
    if (json == null) return null;
    return codec.toJsonNode(codecNode(json)) == null ? null : new tools.jackson.databind.ObjectMapper().treeToValue(codecNode(json), Actor.class);
  }

  private static JsonNode codecNode(String json) {
    return json == null ? null : new tools.jackson.databind.ObjectMapper().readTree(json);
  }

  private static Integer nullableInt(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
    int value = rs.getInt(index);
    return rs.wasNull() ? null : value;
  }
}

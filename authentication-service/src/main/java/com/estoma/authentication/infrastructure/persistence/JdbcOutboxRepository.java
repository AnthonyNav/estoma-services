package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.application.port.OutboxPort;
import com.estoma.authentication.contracts.Actor;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of the Transactional Outbox. {@code append} is the
 * write side used by dispatchers, exposed through {@link OutboxPort}. The
 * claim/publish/mark methods below are the read/relay side -- an
 * infrastructure-internal concern the messaging tramo (only {@code
 * OutboxRelay}) depends on directly, deliberately not exposed through
 * {@code OutboxPort}.
 */
@Repository
public class JdbcOutboxRepository implements OutboxPort {

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  JdbcOutboxRepository(JdbcClient jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void append(OutboxAppendRequest request) {
    jdbc.sql(
            """
            INSERT INTO outbox_messages (
              message_id, message_type, message_version, message_kind, aggregate_id, aggregate_version,
                correlation_id, causation_id, operation_id, idempotency_key, actor, result_requester, payload, status, attempts, occurred_at, available_at
            ) VALUES (
              :messageId, :messageType, :messageVersion, :messageKind, :aggregateId, :aggregateVersion,
               :correlationId, :causationId, :operationId, :idempotencyKey, CAST(:actor AS jsonb), :resultRequester, CAST(:payload AS jsonb),
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
        .param("operationId", request.operationId())
        .param("idempotencyKey", request.idempotencyKey())
        .param("actor", request.actor() == null ? null : mapper.writeValueAsString(request.actor()))
         .param("resultRequester", request.resultRequester())
        .param("payload", request.payloadJson())
        .param("occurredAt", Timestamps.toTimestamp(request.occurredAt()))
        .update();
  }

  /**
   * Claims up to {@code limit} reclaimable rows for this poll tick
   * (system_contract.md "Outbox Operational Policy"). Intended to run
   * inside the same transaction as the caller's subsequent publish/mark
   * calls for these rows: the {@code FOR UPDATE SKIP LOCKED} lock is held
   * until that transaction commits, which is also what makes a crash
   * between a confirmed publish and its {@code markPublished} safe --
   * the whole batch rolls back and the row is simply republished on the
   * next tick (messaging-architecture.md §9: "Outbox no elimina
   * duplicados").
   */
  public List<OutboxRecord> claimBatch(int limit) {
    return jdbc.sql(
            """
            SELECT message_id, message_type, message_version, message_kind, aggregate_id, aggregate_version,
                    correlation_id, causation_id, operation_id, idempotency_key, actor::text AS actor, result_requester, payload::text AS payload, attempts, occurred_at
            FROM outbox_messages
            WHERE status = 'PENDING' AND available_at <= now()
            ORDER BY occurred_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
        .param("limit", limit)
        .query(
            (rs, rowNum) ->
                new OutboxRecord(
                    (UUID) rs.getObject("message_id"),
                    rs.getString("message_type"),
                    rs.getInt("message_version"),
                    rs.getString("message_kind"),
                    (UUID) rs.getObject("aggregate_id"),
                    rs.getObject("aggregate_version") == null ? null : rs.getInt("aggregate_version"),
                    (UUID) rs.getObject("correlation_id"),
                    (UUID) rs.getObject("causation_id"),
                    (UUID) rs.getObject("operation_id"),
                    rs.getString("idempotency_key"),
                    rs.getString("actor") == null ? null : mapper.readValue(rs.getString("actor"), Actor.class),
                     rs.getString("result_requester"),
                     rs.getString("payload"),
                    rs.getInt("attempts"),
                    Timestamps.toInstant(rs, "occurred_at")))
        .list();
  }

  public void markPublished(UUID messageId, Instant publishedAt) {
    jdbc.sql("UPDATE outbox_messages SET status = CAST('PUBLISHED' AS outbox_status), published_at = :publishedAt WHERE message_id = :messageId")
        .param("publishedAt", Timestamps.toTimestamp(publishedAt))
        .param("messageId", messageId)
        .update();
  }

  /** Row stays {@code PENDING}: reclaimable again once {@code availableAt} passes. */
  public void releaseForRetry(UUID messageId, Instant availableAt) {
    jdbc.sql("UPDATE outbox_messages SET attempts = attempts + 1, available_at = :availableAt WHERE message_id = :messageId")
        .param("availableAt", Timestamps.toTimestamp(availableAt))
        .param("messageId", messageId)
        .update();
  }

  public void markFailed(UUID messageId) {
    jdbc.sql("UPDATE outbox_messages SET status = CAST('FAILED' AS outbox_status), attempts = attempts + 1 WHERE message_id = :messageId")
        .param("messageId", messageId)
        .update();
  }

  /** Oldest {@code available_at} among rows overdue for publication, or {@code null} if none -- used by the readiness health check. */
  public Instant oldestOverduePendingAvailableAt() {
    return jdbc.sql("SELECT min(available_at) AS oldest FROM outbox_messages WHERE status = 'PENDING' AND available_at <= now()")
        .query((rs, rowNum) -> Timestamps.toInstant(rs, "oldest"))
        .optional()
        .orElse(null);
  }
}

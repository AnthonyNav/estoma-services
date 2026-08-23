package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.ProcessedMessageRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessedMessageRepository implements ProcessedMessageRepository {
  private final JdbcTemplate jdbc;

  public JdbcProcessedMessageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public boolean insertIfAbsent(ProcessedMessage m) {
    return jdbc.update("""
        INSERT INTO processed_messages (
          consumer_name,message_id,status,message_type,message_version,message_kind,producer,
          correlation_id,causation_id,operation_id,idempotency_key,aggregate_id,aggregate_version,
          actor_json,payload_json,received_at,occurred_at,applied_at,deferred_reason,updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?,?,?,?)
        ON CONFLICT (consumer_name,message_id) DO NOTHING
        """, m.consumerName(), m.messageId(), m.status().name(), m.messageType(), m.messageVersion(),
        m.messageKind(), m.producer(), m.correlationId(), m.causationId(), m.operationId(),
        m.idempotencyKey(), m.aggregateId(), m.aggregateVersion(), m.actorJson(), m.payloadJson(),
        JdbcTemporal.timestamp(m.receivedAt()), JdbcTemporal.timestamp(m.occurredAt()),
        JdbcTemporal.timestamp(m.appliedAt()), m.deferredReason(), JdbcTemporal.timestamp(m.updatedAt())) == 1;
  }

  @Override
  public Optional<ProcessedMessage> find(String consumerName, UUID messageId) {
    return jdbc.query("SELECT * FROM processed_messages WHERE consumer_name=? AND message_id=?",
        this::map, consumerName, messageId).stream().findFirst();
  }

  @Override
  public boolean claimDeferred(String consumerName, UUID messageId, Instant now, UUID claimToken, Instant leaseUntil) {
    validateClaim(now, claimToken, leaseUntil);
    return jdbc.update("""
        UPDATE processed_messages
        SET claim_token=?,lease_until=?,updated_at=?
        WHERE consumer_name=? AND message_id=? AND status='DEFERRED'
          AND (lease_until IS NULL OR lease_until <= ?)
        """, claimToken, JdbcTemporal.timestamp(leaseUntil), JdbcTemporal.timestamp(now), consumerName,
        messageId, JdbcTemporal.timestamp(now)) == 1;
  }

  @Override
  public List<ProcessedMessage> claimDeferred(
      String consumerName, int limit, Instant now, UUID claimToken, Instant leaseUntil) {
    if (limit < 1) throw new IllegalArgumentException("INVALID_DEFERRED_CLAIM");
    validateClaim(now, claimToken, leaseUntil);
    return jdbc.query("""
        WITH candidates AS (
          SELECT consumer_name,message_id
          FROM processed_messages
          WHERE consumer_name=? AND status='DEFERRED' AND (lease_until IS NULL OR lease_until <= ?)
          ORDER BY received_at,message_id
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        UPDATE processed_messages p
        SET claim_token=?, lease_until=?, updated_at=?
        FROM candidates c
        WHERE p.consumer_name=c.consumer_name AND p.message_id=c.message_id
        RETURNING p.*
        """, this::map, consumerName, JdbcTemporal.timestamp(now), limit, claimToken,
        JdbcTemporal.timestamp(leaseUntil), JdbcTemporal.timestamp(now));
  }

  @Override
  public boolean markApplied(String consumerName, UUID messageId, UUID claimToken, Instant appliedAt) {
    return jdbc.update("""
        UPDATE processed_messages
        SET status='APPLIED', applied_at=?, deferred_reason=NULL, claim_token=NULL, lease_until=NULL, updated_at=?
        WHERE consumer_name=? AND message_id=? AND status='DEFERRED' AND claim_token=?
        """, JdbcTemporal.timestamp(appliedAt), JdbcTemporal.timestamp(appliedAt), consumerName,
        messageId, claimToken) == 1;
  }

  @Override
  public boolean keepDeferred(String consumerName, UUID messageId, UUID claimToken, String reason, Instant updatedAt) {
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("DEFERRED_REASON_REQUIRED");
    return jdbc.update("""
        UPDATE processed_messages
        SET deferred_reason=?,claim_token=NULL,lease_until=NULL,updated_at=?
        WHERE consumer_name=? AND message_id=? AND status='DEFERRED' AND claim_token=?
        """, reason, JdbcTemporal.timestamp(updatedAt), consumerName, messageId, claimToken) == 1;
  }

  private ProcessedMessage map(ResultSet rs, int row) throws SQLException {
    return new ProcessedMessage(
        rs.getString("consumer_name"), rs.getObject("message_id", UUID.class),
        Status.valueOf(rs.getString("status")), rs.getString("message_type"), rs.getInt("message_version"),
        rs.getString("message_kind"), rs.getString("producer"), rs.getObject("correlation_id", UUID.class),
        rs.getObject("causation_id", UUID.class), rs.getObject("operation_id", UUID.class),
        rs.getString("idempotency_key"), rs.getObject("aggregate_id", UUID.class),
        nullableLong(rs, "aggregate_version"), rs.getString("actor_json"), rs.getString("payload_json"),
        rs.getTimestamp("received_at").toInstant(), rs.getTimestamp("occurred_at").toInstant(),
        instant(rs, "applied_at"), rs.getString("deferred_reason"), rs.getTimestamp("updated_at").toInstant());
  }

  private static void validateClaim(Instant now, UUID claimToken, Instant leaseUntil) {
    if (now == null || claimToken == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
      throw new IllegalArgumentException("INVALID_DEFERRED_CLAIM");
    }
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}

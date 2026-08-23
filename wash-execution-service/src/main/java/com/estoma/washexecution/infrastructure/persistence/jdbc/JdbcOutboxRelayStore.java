package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.infrastructure.messaging.outbound.OutboxRelayStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcOutboxRelayStore implements OutboxRelayStore {
  private final JdbcTemplate jdbc;

  public JdbcOutboxRelayStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<RelayMessage> claimBatch(int limit, Instant now, UUID claimToken, Instant leaseUntil) {
    return jdbc.query("""
        WITH candidate AS (
          SELECT message_id
          FROM outbox_messages
          WHERE (status='PENDING' AND available_at <= ?)
             OR (status='PROCESSING' AND lease_until <= ?)
          ORDER BY created_at,message_id
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        UPDATE outbox_messages o
        SET status='PROCESSING',claim_token=?,lease_until=?,attempt_count=o.attempt_count+1
        FROM candidate c
        WHERE o.message_id=c.message_id
        RETURNING o.*
        """, this::map, now, now, limit, claimToken, leaseUntil);
  }

  @Override
  public boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt) {
    return jdbc.update("""
        UPDATE outbox_messages
        SET status='PUBLISHED',published_at=?,claim_token=NULL,lease_until=NULL,last_error=NULL
        WHERE message_id=? AND status='PROCESSING' AND claim_token=?
        """, publishedAt, messageId, claimToken) == 1;
  }

  @Override
  public boolean reschedule(UUID messageId, UUID claimToken, Instant availableAt, String error) {
    return jdbc.update("""
        UPDATE outbox_messages
        SET status='PENDING',available_at=?,last_error=?,claim_token=NULL,lease_until=NULL
        WHERE message_id=? AND status='PROCESSING' AND claim_token=?
        """, availableAt, error, messageId, claimToken) == 1;
  }

  @Override
  public boolean markFailed(UUID messageId, UUID claimToken, String error) {
    return jdbc.update("""
        UPDATE outbox_messages
        SET status='FAILED',last_error=?,claim_token=NULL,lease_until=NULL
        WHERE message_id=? AND status='PROCESSING' AND claim_token=?
        """, error, messageId, claimToken) == 1;
  }

  private RelayMessage map(ResultSet rs, int row) throws SQLException {
    return new RelayMessage(
        rs.getObject("message_id", UUID.class),
        rs.getString("message_type"),
        rs.getInt("message_version"),
        rs.getString("message_kind"),
        rs.getString("producer"),
        rs.getString("routing_key"),
        rs.getTimestamp("occurred_at").toInstant(),
        rs.getObject("operation_id", UUID.class),
        rs.getString("idempotency_key"),
        rs.getObject("aggregate_id", UUID.class),
        (Integer) rs.getObject("aggregate_version"),
        rs.getObject("correlation_id", UUID.class),
        rs.getObject("causation_id", UUID.class),
        rs.getString("actor_json"),
        rs.getString("payload_json"),
        rs.getInt("attempt_count"));
  }
}

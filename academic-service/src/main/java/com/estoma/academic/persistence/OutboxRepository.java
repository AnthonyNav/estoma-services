package com.estoma.academic.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
  private final JdbcClient jdbc;
  public OutboxRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  public void append(UUID id, String type, String kind, UUID aggregateId, Integer version, UUID correlationId,
      UUID causationId, String payload, String metadata, Instant now) {
    jdbc.sql("""
        INSERT INTO outbox_messages (message_id,message_type,message_version,message_kind,aggregate_id,aggregate_version,correlation_id,causation_id,payload,metadata,status,attempts,occurred_at,available_at)
        VALUES (:id,:type,1,:kind,:aggregateId,:version,:correlationId,:causationId,CAST(:payload AS jsonb),CAST(:metadata AS jsonb),'PENDING',0,:now,:now)
        """)
        .param("id", id).param("type", type).param("kind", kind).param("aggregateId", aggregateId).param("version", version)
        .param("correlationId", correlationId).param("causationId", causationId).param("payload", payload).param("metadata", metadata).param("now", java.sql.Timestamp.from(now)).update();
  }

  public List<Row> claim(int limit, Duration lease) {
    UUID token = UUID.randomUUID(); Instant now = Instant.now();
    return jdbc.sql("""
        WITH candidates AS (SELECT message_id FROM outbox_messages WHERE status='PENDING' AND available_at <= :now ORDER BY available_at FOR UPDATE SKIP LOCKED LIMIT :limit)
        UPDATE outbox_messages o SET status='PROCESSING', attempts=attempts+1, claim_token=:token, lease_until=:lease FROM candidates c WHERE o.message_id=c.message_id
        RETURNING o.message_id,o.message_type,o.message_kind,o.aggregate_id,o.aggregate_version,o.correlation_id,o.causation_id,o.payload::text,o.metadata::text,o.claim_token,o.occurred_at,o.attempts
        """)
        .param("now", java.sql.Timestamp.from(now)).param("limit", limit).param("token", token).param("lease", java.sql.Timestamp.from(now.plus(lease)))
        .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
             (Integer) rs.getObject(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9), rs.getObject(10, UUID.class), rs.getTimestamp(11).toInstant(), rs.getInt(12))).list();
  }
  public int published(Row row) { return jdbc.sql("UPDATE outbox_messages SET status='PUBLISHED',published_at=now(),claim_token=NULL,lease_until=NULL,last_error_code=NULL,last_error_at=NULL WHERE message_id=:id AND claim_token=:token AND status='PROCESSING'").param("id", row.id()).param("token", row.token()).update(); }
  public int retry(Row row, Duration delay, String code) { return jdbc.sql("UPDATE outbox_messages SET status='PENDING',available_at=:available,claim_token=NULL,lease_until=NULL,last_error_code=:code,last_error_at=now() WHERE message_id=:id AND claim_token=:token AND status='PROCESSING'").param("id", row.id()).param("token", row.token()).param("available", java.sql.Timestamp.from(Instant.now().plus(delay))).param("code", code).update(); }
  public int failed(Row row, String code) { return jdbc.sql("UPDATE outbox_messages SET status='FAILED',claim_token=NULL,lease_until=NULL,last_error_code=:code,last_error_at=now() WHERE message_id=:id AND claim_token=:token AND status='PROCESSING'").param("id",row.id()).param("token",row.token()).param("code",code).update(); }
  public int recoverExpired(int maxAttempts) { return jdbc.sql("UPDATE outbox_messages SET status=CASE WHEN attempts >= :max THEN 'FAILED'::outbox_status ELSE 'PENDING'::outbox_status END,available_at=CASE WHEN attempts >= :max THEN available_at ELSE now() END,claim_token=NULL,lease_until=NULL,last_error_code='LEASE_EXPIRED',last_error_at=now() WHERE status='PROCESSING' AND lease_until < now()").param("max",maxAttempts).update(); }
  public long overdue() { return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE status IN ('PENDING','PROCESSING') AND available_at < now()-interval '5 minutes'").query(Long.class).single(); }
  public long pendingCount() { return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE status='PENDING'").query(Long.class).single(); }
  public long stuckProcessingCount() { return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE status='PROCESSING' AND lease_until < now()").query(Long.class).single(); }
  public Instant oldestPendingAvailableAt() {
    return jdbc.sql("SELECT min(available_at) FROM outbox_messages WHERE status='PENDING'")
        .query((rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
        .optional().orElse(null);
  }
  public record Row(UUID id, String type, String kind, UUID aggregateId, Integer aggregateVersion, UUID correlationId, UUID causationId, String payload, String metadata, UUID token, Instant occurredAt, int attempts) {}
}

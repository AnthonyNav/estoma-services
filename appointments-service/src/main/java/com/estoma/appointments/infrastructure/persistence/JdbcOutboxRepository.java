package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {
  private final JdbcClient jdbc;
  public JdbcOutboxRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public void append(OutboxMessage m) {
    jdbc.sql("INSERT INTO outbox_messages (message_id,message_type,message_version,message_kind,aggregate_id,aggregate_version,correlation_id,causation_id,operation_id,payload,occurred_at,available_at) VALUES (:id,:type,:version,:kind,:aggregate,:aggregateVersion,:correlation,:causation,:operation,CAST(:payload AS jsonb),:occurred,:available) ON CONFLICT (message_id) DO NOTHING")
      .param("id",m.messageId()).param("type",m.messageType()).param("version",m.messageVersion())
      .param("kind",m.messageKind()).param("aggregate",m.aggregateId()).param("aggregateVersion",m.aggregateVersion())
      .param("correlation",m.correlationId()).param("causation",m.causationId()).param("operation",m.operationId())
      .param("payload",m.payloadJson()).param("occurred",Timestamps.toTimestamp(m.occurredAt()))
      .param("available",Timestamps.toTimestamp(m.availableAt())).update();
  }

  @Override public List<OutboxMessage> claimBatch(int limit, String workerId, Instant now, Instant leaseUntil) {
    return jdbc.sql("""
      WITH candidates AS (
        SELECT message_id FROM outbox_messages
        WHERE (status='PENDING' OR (status='PROCESSING' AND lease_until < :now))
          AND available_at <= :now
        ORDER BY occurred_at
        FOR UPDATE SKIP LOCKED LIMIT :limit
      )
      UPDATE outbox_messages o
      SET status='PROCESSING',claimed_by=:worker,lease_until=:leaseUntil,attempts=o.attempts+1
      FROM candidates c WHERE o.message_id=c.message_id
      RETURNING o.message_id,o.message_type,o.message_version,o.message_kind,o.aggregate_id,
        o.aggregate_version,o.correlation_id,o.causation_id,o.operation_id,o.payload::text AS payload,
        o.occurred_at,o.available_at,o.status,o.attempts,o.claimed_by,o.lease_until
      """)
      .param("now",Timestamps.toTimestamp(now)).param("limit",limit).param("worker",workerId)
      .param("leaseUntil",Timestamps.toTimestamp(leaseUntil))
      .query(JdbcOutboxRepository::map).list();
  }

  private static OutboxMessage map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new OutboxMessage(rs.getObject("message_id",UUID.class),rs.getString("message_type"),
        rs.getInt("message_version"),rs.getString("message_kind"),rs.getObject("aggregate_id",UUID.class),
        rs.getObject("aggregate_version",Long.class),rs.getObject("correlation_id",UUID.class),
        rs.getObject("causation_id",UUID.class),rs.getString("payload"),
        Timestamps.toInstant(rs,"occurred_at"),Timestamps.toInstant(rs,"available_at"),
        rs.getString("status"),rs.getInt("attempts"),rs.getString("claimed_by"),
        Timestamps.toInstant(rs,"lease_until"),rs.getObject("operation_id",UUID.class));
  }

  @Override public boolean markPublished(UUID id, String worker, Instant at) {
    return jdbc.sql("UPDATE outbox_messages SET status='PUBLISHED',published_at=:at,claimed_by=NULL,lease_until=NULL WHERE message_id=:id AND status='PROCESSING' AND claimed_by=:worker")
      .param("id",id).param("worker",worker).param("at",Timestamps.toTimestamp(at)).update()==1;
  }

  @Override public boolean markFailed(UUID id, String worker, String error, Instant next) {
    return jdbc.sql("UPDATE outbox_messages SET status='PENDING',available_at=:next,last_error=:error,claimed_by=NULL,lease_until=NULL WHERE message_id=:id AND status='PROCESSING' AND claimed_by=:worker")
      .param("id",id).param("worker",worker).param("error",error).param("next",Timestamps.toTimestamp(next)).update()==1;
  }

  @Override public boolean quarantine(UUID id, String worker, String error, Instant at) {
    return jdbc.sql("UPDATE outbox_messages SET status='FAILED',last_error=:error,claimed_by=NULL,lease_until=NULL WHERE message_id=:id AND status='PROCESSING' AND claimed_by=:worker")
      .param("id", id).param("worker", worker).param("error", error).update() == 1;
  }
}

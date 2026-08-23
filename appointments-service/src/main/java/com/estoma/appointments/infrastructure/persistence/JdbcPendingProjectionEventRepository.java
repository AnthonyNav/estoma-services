package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.PendingProjectionEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPendingProjectionEventRepository implements PendingProjectionEventRepository {
  private final JdbcClient jdbc;
  public JdbcPendingProjectionEventRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public void defer(PendingProjectionEvent e) {
    jdbc.sql("""
      INSERT INTO pending_projection_events
        (pending_projection_event_id,source_event_id,projection_name,message_type,aggregate_version,envelope_json,status,available_at,last_error)
      VALUES (:id,:source,:projection,:type,:version,CAST(:envelope AS jsonb),'PENDING',:available,NULL)
      ON CONFLICT (source_event_id) DO UPDATE SET
        envelope_json=EXCLUDED.envelope_json,
        aggregate_version=EXCLUDED.aggregate_version,
        available_at=LEAST(pending_projection_events.available_at,EXCLUDED.available_at),
        status=CASE WHEN pending_projection_events.status='COMPLETED' THEN 'COMPLETED' ELSE 'PENDING' END,
        claimed_by=NULL,lease_until=NULL
      """)
      .param("id",e.pendingEventId()).param("source",e.sourceEventId())
      .param("projection",e.projectionName()).param("type",e.messageType())
      .param("version",e.aggregateVersion()).param("envelope",e.envelopeJson())
      .param("available",Timestamps.toTimestamp(e.availableAt())).update();
  }

  @Override public List<PendingProjectionEvent> claim(String projection, int limit, String worker,
      Instant now, Instant leaseUntil) {
    return jdbc.sql("""
      WITH candidates AS (
        SELECT pending_projection_event_id FROM pending_projection_events
        WHERE projection_name=:projection
          AND (status='PENDING' OR (status='PROCESSING' AND lease_until < :now))
          AND available_at<=:now
        ORDER BY aggregate_version NULLS FIRST, available_at, pending_projection_event_id
        FOR UPDATE SKIP LOCKED LIMIT :limit
      )
      UPDATE pending_projection_events p
      SET status='PROCESSING',claimed_by=:worker,lease_until=:leaseUntil,attempts=p.attempts+1
      FROM candidates c WHERE p.pending_projection_event_id=c.pending_projection_event_id
      RETURNING p.pending_projection_event_id,p.source_event_id,p.projection_name,p.message_type,
        p.aggregate_version,p.envelope_json::text AS envelope_json,p.status,p.attempts,p.available_at,
        p.claimed_by,p.lease_until
      """)
      .param("projection",projection).param("now",Timestamps.toTimestamp(now)).param("limit",limit)
      .param("worker",worker).param("leaseUntil",Timestamps.toTimestamp(leaseUntil))
      .query((rs,row)->new PendingProjectionEvent(rs.getObject("pending_projection_event_id",UUID.class),
          rs.getObject("source_event_id",UUID.class),rs.getString("projection_name"),rs.getString("message_type"),
          rs.getObject("aggregate_version",Long.class),rs.getString("envelope_json"),rs.getString("status"),
          rs.getInt("attempts"),Timestamps.toInstant(rs,"available_at"),rs.getString("claimed_by"),
          Timestamps.toInstant(rs,"lease_until"))).list();
  }

  @Override public boolean completeBySourceEvent(UUID sourceEventId, Instant at) {
    return jdbc.sql("UPDATE pending_projection_events SET status='COMPLETED',completed_at=:at,claimed_by=NULL,lease_until=NULL,last_error=NULL WHERE source_event_id=:source AND status <> 'COMPLETED'")
      .param("source",sourceEventId).param("at",Timestamps.toTimestamp(at)).update()==1;
  }

  @Override public boolean fail(UUID id, String worker, String error, Instant availableAt) {
    return jdbc.sql("UPDATE pending_projection_events SET status='PENDING',available_at=:available,last_error=:error,claimed_by=NULL,lease_until=NULL WHERE pending_projection_event_id=:id AND status='PROCESSING' AND claimed_by=:worker")
      .param("id",id).param("worker",worker).param("error",error)
      .param("available",Timestamps.toTimestamp(availableAt)).update()==1;
  }
}

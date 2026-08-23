package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.PendingWashExecutionEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPendingWashExecutionEventRepository implements PendingWashExecutionEventRepository {
  private final JdbcClient jdbc;
  public JdbcPendingWashExecutionEventRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override public void defer(PendingEvent e) {
    jdbc.sql("INSERT INTO pending_wash_execution_events (pending_event_id,source_event_id,appointment_id,wash_execution_id,event_type,execution_version,payload,available_at) VALUES (:id,:source,:appointment,:execution,:type,:version,CAST(:payload AS jsonb),:available) ON CONFLICT (source_event_id) DO UPDATE SET available_at=EXCLUDED.available_at,status='PENDING',claimed_by=NULL,lease_until=NULL")
      .param("id",e.pendingEventId()).param("source",e.sourceEventId()).param("appointment",e.appointmentId()).param("execution",e.washExecutionId()).param("type",e.eventType()).param("version",e.executionVersion()).param("payload",e.payloadJson()).param("available",Timestamps.toTimestamp(e.availableAt())).update();
  }
  @Override public List<PendingEvent> claim(int limit, String worker, Instant now, Instant leaseUntil) {
    return jdbc.sql("""
      WITH candidates AS (SELECT pending_event_id FROM pending_wash_execution_events WHERE (status='PENDING' OR (status='PROCESSING' AND lease_until < :now)) AND available_at<=:now ORDER BY available_at FOR UPDATE SKIP LOCKED LIMIT :limit)
      UPDATE pending_wash_execution_events p SET status='PROCESSING',claimed_by=:worker,lease_until=:leaseUntil,attempts=p.attempts+1 FROM candidates c WHERE p.pending_event_id=c.pending_event_id
      RETURNING p.pending_event_id,p.source_event_id,p.appointment_id,p.wash_execution_id,p.event_type,p.execution_version,p.payload::text AS payload,p.status,p.attempts,p.available_at,p.claimed_by,p.lease_until
      """).param("now",Timestamps.toTimestamp(now)).param("worker",worker).param("leaseUntil",Timestamps.toTimestamp(leaseUntil)).param("limit",limit).query((rs,row)->new PendingEvent(rs.getObject("pending_event_id",UUID.class),rs.getObject("source_event_id",UUID.class),rs.getObject("appointment_id",UUID.class),rs.getObject("wash_execution_id",UUID.class),rs.getString("event_type"),rs.getLong("execution_version"),rs.getString("payload"),rs.getString("status"),rs.getInt("attempts"),Timestamps.toInstant(rs,"available_at"),rs.getString("claimed_by"),Timestamps.toInstant(rs,"lease_until"))).list();
  }
  @Override public boolean complete(UUID id, String worker, Instant at) { return jdbc.sql("UPDATE pending_wash_execution_events SET status='COMPLETED',completed_at=:at,claimed_by=NULL,lease_until=NULL WHERE pending_event_id=:id AND status='PROCESSING' AND claimed_by=:worker").param("id",id).param("worker",worker).param("at",Timestamps.toTimestamp(at)).update()==1; }
}

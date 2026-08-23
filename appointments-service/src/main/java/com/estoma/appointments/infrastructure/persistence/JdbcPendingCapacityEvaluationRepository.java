package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.PendingCapacityEvaluationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPendingCapacityEvaluationRepository implements PendingCapacityEvaluationRepository {
  private final JdbcClient jdbc;
  public JdbcPendingCapacityEvaluationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public void enqueue(PendingEvaluation e) {
    jdbc.sql("""
      INSERT INTO pending_capacity_evaluation
        (evaluation_id,source_event_id,appointment_time_slot_id,wash_execution_id,appointment_id,cause,cleaning_trigger,available_at)
      VALUES (:id,:source,:slot,:execution,:appointment,:cause,:trigger,:available)
      ON CONFLICT (source_event_id,appointment_time_slot_id) DO NOTHING
      """)
      .param("id", e.evaluationId())
      .param("source", e.sourceEventId())
      .param("slot", e.slotId())
      .param("execution", e.washExecutionId())
      .param("appointment", e.appointmentId())
      .param("cause", e.cause())
      .param("trigger", e.cleaningTrigger())
      .param("available", Timestamps.toTimestamp(e.availableAt()))
      .update();
  }

  @Override public List<PendingEvaluation> claim(int limit, String worker, Instant now, Instant leaseUntil) {
    return jdbc.sql("""
      WITH candidates AS (
        SELECT evaluation_id FROM pending_capacity_evaluation
        WHERE (status='PENDING' OR (status='PROCESSING' AND lease_until < :now))
          AND available_at<=:now
        ORDER BY available_at, evaluation_id
        FOR UPDATE SKIP LOCKED
        LIMIT :limit
      )
      UPDATE pending_capacity_evaluation p
      SET status='PROCESSING',claimed_by=:worker,lease_until=:leaseUntil,attempts=p.attempts+1
      FROM candidates c
      WHERE p.evaluation_id=c.evaluation_id
      RETURNING p.evaluation_id,p.source_event_id,p.appointment_time_slot_id,p.wash_execution_id,
        p.appointment_id,p.cause,p.cleaning_trigger,p.status,p.attempts,p.available_at,p.claimed_by,p.lease_until
      """)
      .param("now", Timestamps.toTimestamp(now))
      .param("worker", worker)
      .param("leaseUntil", Timestamps.toTimestamp(leaseUntil))
      .param("limit", limit)
      .query((rs,row) -> new PendingEvaluation(
          rs.getObject("evaluation_id", UUID.class),
          rs.getObject("source_event_id", UUID.class),
          rs.getObject("appointment_time_slot_id", UUID.class),
          rs.getObject("wash_execution_id", UUID.class),
          rs.getObject("appointment_id", UUID.class),
          rs.getString("cause"),
          rs.getString("cleaning_trigger"),
          rs.getString("status"),
          rs.getInt("attempts"),
          Timestamps.toInstant(rs,"available_at"),
          rs.getString("claimed_by"),
          Timestamps.toInstant(rs,"lease_until")))
      .list();
  }

  @Override public boolean complete(UUID id, String worker, Instant at) {
    return jdbc.sql("""
      UPDATE pending_capacity_evaluation
      SET status='COMPLETED',completed_at=:at,claimed_by=NULL,lease_until=NULL
      WHERE evaluation_id=:id AND status='PROCESSING' AND claimed_by=:worker
      """)
      .param("id",id).param("worker",worker).param("at",Timestamps.toTimestamp(at)).update()==1;
  }
}

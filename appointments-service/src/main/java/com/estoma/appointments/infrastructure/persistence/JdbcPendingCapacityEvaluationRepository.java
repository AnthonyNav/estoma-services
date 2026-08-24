package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.PendingCapacityEvaluationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPendingCapacityEvaluationRepository implements PendingCapacityEvaluationRepository {
  private static final String RETURNING = """
      RETURNING p.evaluation_id,p.source_event_id,p.appointment_time_slot_id,p.wash_execution_id,
        p.appointment_id,p.cause,p.cleaning_trigger,
        p.previous_normal_capacity,p.current_normal_capacity,
        p.previous_journey_capacity,p.current_journey_capacity,
        p.previous_immunocompromised_capacity,p.current_immunocompromised_capacity,
        p.projection_version,p.status,p.attempts,p.available_at,p.claimed_by,p.lease_until
      """;

  private final JdbcClient jdbc;
  public JdbcPendingCapacityEvaluationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public void enqueue(PendingEvaluation e) {
    jdbc.sql("""
      INSERT INTO pending_capacity_evaluation
        (evaluation_id,source_event_id,appointment_time_slot_id,wash_execution_id,appointment_id,
         cause,cleaning_trigger,previous_normal_capacity,current_normal_capacity,
         previous_journey_capacity,current_journey_capacity,previous_immunocompromised_capacity,
         current_immunocompromised_capacity,projection_version,available_at)
      VALUES (:id,:source,:slot,:execution,:appointment,:cause,:trigger,:previousNormal,:currentNormal,
              :previousJourney,:currentJourney,:previousImmuno,:currentImmuno,:projectionVersion,:available)
      ON CONFLICT (source_event_id,appointment_time_slot_id) DO NOTHING
      """)
      .param("id", e.evaluationId())
      .param("source", e.sourceEventId())
      .param("slot", e.slotId())
      .param("execution", e.washExecutionId())
      .param("appointment", e.appointmentId())
      .param("cause", e.cause())
      .param("trigger", e.cleaningTrigger())
      .param("previousNormal", e.previousNormalCapacity())
      .param("currentNormal", e.currentNormalCapacity())
      .param("previousJourney", e.previousJourneyCapacity())
      .param("currentJourney", e.currentJourneyCapacity())
      .param("previousImmuno", e.previousImmunocompromisedCapacity())
      .param("currentImmuno", e.currentImmunocompromisedCapacity())
      .param("projectionVersion", e.projectionVersion())
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
      """ + RETURNING)
      .param("now", Timestamps.toTimestamp(now))
      .param("worker", worker)
      .param("leaseUntil", Timestamps.toTimestamp(leaseUntil))
      .param("limit", limit)
      .query(this::map)
      .list();
  }

  @Override
  public List<PendingEvaluation> claimForExecution(UUID washExecutionId, int limit, String worker,
      Instant now, Instant leaseUntil) {
    return jdbc.sql("""
      WITH candidates AS (
        SELECT evaluation_id FROM pending_capacity_evaluation
        WHERE wash_execution_id=:execution
          AND (status='PENDING' OR (status='PROCESSING' AND lease_until < :now))
          AND available_at<=:now
        ORDER BY appointment_time_slot_id,evaluation_id
        FOR UPDATE SKIP LOCKED
        LIMIT :limit
      )
      UPDATE pending_capacity_evaluation p
      SET status='PROCESSING',claimed_by=:worker,lease_until=:leaseUntil,attempts=p.attempts+1
      FROM candidates c
      WHERE p.evaluation_id=c.evaluation_id
      """ + RETURNING)
      .param("execution", washExecutionId)
      .param("now", Timestamps.toTimestamp(now))
      .param("worker", worker)
      .param("leaseUntil", Timestamps.toTimestamp(leaseUntil))
      .param("limit", limit)
      .query(this::map)
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

  private PendingEvaluation map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    Long projectionVersion = rs.getObject("projection_version", Long.class);
    return new PendingEvaluation(
        rs.getObject("evaluation_id", UUID.class),
        rs.getObject("source_event_id", UUID.class),
        rs.getObject("appointment_time_slot_id", UUID.class),
        rs.getObject("wash_execution_id", UUID.class),
        rs.getObject("appointment_id", UUID.class),
        rs.getString("cause"),
        rs.getString("cleaning_trigger"),
        rs.getObject("previous_normal_capacity", Integer.class),
        rs.getObject("current_normal_capacity", Integer.class),
        rs.getObject("previous_journey_capacity", Integer.class),
        rs.getObject("current_journey_capacity", Integer.class),
        rs.getObject("previous_immunocompromised_capacity", Integer.class),
        rs.getObject("current_immunocompromised_capacity", Integer.class),
        projectionVersion == null ? 1L : projectionVersion,
        rs.getString("status"),
        rs.getInt("attempts"),
        Timestamps.toInstant(rs,"available_at"),
        rs.getString("claimed_by"),
        Timestamps.toInstant(rs,"lease_until"));
  }
}

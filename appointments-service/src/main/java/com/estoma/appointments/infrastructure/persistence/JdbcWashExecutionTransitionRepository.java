package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.WashExecutionTransitionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWashExecutionTransitionRepository implements WashExecutionTransitionRepository {
  private final JdbcClient jdbc;
  public JdbcWashExecutionTransitionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override public boolean record(TransitionRecord t) {
    return jdbc.sql("INSERT INTO wash_execution_transition_projection (transition_id,wash_execution_id,appointment_id,event_type,execution_version,source_event_id,state,occurred_at,applied_at) VALUES (:id,:execution,:appointment,:type,:executionVersion,:source,:state,:occurred,:applied) ON CONFLICT (source_event_id) DO NOTHING")
      .param("id",t.transitionId()).param("execution",t.washExecutionId()).param("appointment",t.appointmentId()).param("type",t.eventType()).param("executionVersion",t.executionVersion()).param("source",t.sourceEventId()).param("state",t.state()).param("occurred",Timestamps.toTimestamp(t.occurredAt())).param("applied",Timestamps.toTimestamp(t.appliedAt())).update()==1;
  }
  @Override public Optional<TransitionRecord> find(UUID appointmentId, String eventType) {
    return jdbc.sql("SELECT transition_id,wash_execution_id,appointment_id,event_type,execution_version,source_event_id,state,occurred_at,applied_at FROM wash_execution_transition_projection WHERE appointment_id=:appointment AND event_type=:type ORDER BY execution_version DESC LIMIT 1")
      .param("appointment",appointmentId).param("type",eventType).query((rs,row)->new TransitionRecord(rs.getObject("transition_id",UUID.class),rs.getObject("wash_execution_id",UUID.class),rs.getObject("appointment_id",UUID.class),rs.getString("event_type"),rs.getLong("execution_version"),rs.getObject("source_event_id",UUID.class),rs.getString("state"),Timestamps.toInstant(rs,"occurred_at"),Timestamps.toInstant(rs,"applied_at"))).optional();
  }
}

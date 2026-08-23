package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.WashExecutionEvidenceRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWashExecutionEvidenceRepository implements WashExecutionEvidenceRepository {
  private final JdbcClient jdbc;
  public JdbcWashExecutionEvidenceRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public EvidenceRecord recordArrival(EvidenceRecord e) {
    jdbc.sql("""
      INSERT INTO wash_execution_evidence_projection
        (evidence_id,wash_execution_id,appointment_id,arrived_at,arrival_execution_version,
         arrival_source_event_id,last_source_event_id,last_source_event_type,updated_at)
      VALUES (:id,:execution,:appointment,:arrived,:arrivalVersion,:source,:source,'WashArrivalRegistered',:updated)
      ON CONFLICT (wash_execution_id,appointment_id) DO UPDATE SET
        arrived_at=EXCLUDED.arrived_at,
        arrival_execution_version=EXCLUDED.arrival_execution_version,
        arrival_source_event_id=EXCLUDED.arrival_source_event_id,
        last_source_event_id=EXCLUDED.last_source_event_id,
        last_source_event_type=EXCLUDED.last_source_event_type,
        updated_at=EXCLUDED.updated_at
      WHERE wash_execution_evidence_projection.arrival_execution_version IS NULL
         OR wash_execution_evidence_projection.arrival_execution_version < EXCLUDED.arrival_execution_version
         OR (wash_execution_evidence_projection.arrival_execution_version = EXCLUDED.arrival_execution_version
             AND wash_execution_evidence_projection.arrival_source_event_id = EXCLUDED.arrival_source_event_id)
      """)
      .param("id",e.evidenceId()).param("execution",e.washExecutionId())
      .param("appointment",e.appointmentId()).param("arrived",Timestamps.toTimestamp(e.arrivedAt()))
      .param("arrivalVersion",e.arrivalExecutionVersion()).param("source",e.sourceEventId())
      .param("updated",Timestamps.toTimestamp(e.updatedAt())).update();
    return e;
  }

  @Override public EvidenceRecord recordEntryAuthorization(EvidenceRecord e) {
    jdbc.sql("""
      INSERT INTO wash_execution_evidence_projection
        (evidence_id,wash_execution_id,appointment_id,entry_authorized_at,entry_authorized_execution_version,
         entry_authorized_source_event_id,last_source_event_id,last_source_event_type,updated_at)
      VALUES (:id,:execution,:appointment,:authorized,:entryVersion,:source,:source,'WashEntryAuthorized',:updated)
      ON CONFLICT (wash_execution_id,appointment_id) DO UPDATE SET
        entry_authorized_at=EXCLUDED.entry_authorized_at,
        entry_authorized_execution_version=EXCLUDED.entry_authorized_execution_version,
        entry_authorized_source_event_id=EXCLUDED.entry_authorized_source_event_id,
        last_source_event_id=EXCLUDED.last_source_event_id,
        last_source_event_type=EXCLUDED.last_source_event_type,
        updated_at=EXCLUDED.updated_at
      WHERE wash_execution_evidence_projection.entry_authorized_execution_version IS NULL
         OR wash_execution_evidence_projection.entry_authorized_execution_version < EXCLUDED.entry_authorized_execution_version
         OR (wash_execution_evidence_projection.entry_authorized_execution_version = EXCLUDED.entry_authorized_execution_version
             AND wash_execution_evidence_projection.entry_authorized_source_event_id = EXCLUDED.entry_authorized_source_event_id)
      """)
      .param("id",e.evidenceId()).param("execution",e.washExecutionId())
      .param("appointment",e.appointmentId()).param("authorized",Timestamps.toTimestamp(e.entryAuthorizedAt()))
      .param("entryVersion",e.entryAuthorizedExecutionVersion()).param("source",e.sourceEventId())
      .param("updated",Timestamps.toTimestamp(e.updatedAt())).update();
    return e;
  }

  @Override public Optional<EvidenceRecord> findByAppointmentId(UUID appointmentId) {
    return find("appointment_id=:appointment", "appointment", appointmentId);
  }

  @Override public Optional<EvidenceRecord> findByExecutionId(UUID executionId) {
    return find("wash_execution_id=:execution", "execution", executionId);
  }

  private Optional<EvidenceRecord> find(String predicate, String parameter, UUID value) {
    return jdbc.sql("SELECT * FROM wash_execution_evidence_projection WHERE " + predicate)
      .param(parameter,value)
      .query((rs,row)->new EvidenceRecord(
          rs.getObject("evidence_id",UUID.class),rs.getObject("wash_execution_id",UUID.class),
          rs.getObject("appointment_id",UUID.class),Timestamps.toInstant(rs,"arrived_at"),
          rs.getObject("arrival_execution_version",Long.class),Timestamps.toInstant(rs,"entry_authorized_at"),
          rs.getObject("entry_authorized_execution_version",Long.class),
          mostRecentSourceEventId(rs), mostRecentSourceEventType(rs), Timestamps.toInstant(rs,"updated_at")))
      .optional();
  }

  private static UUID mostRecentSourceEventId(java.sql.ResultSet rs) throws java.sql.SQLException {
    return rs.getObject("last_source_event_id", UUID.class);
  }

  private static String mostRecentSourceEventType(java.sql.ResultSet rs) throws java.sql.SQLException {
    return rs.getString("last_source_event_type");
  }

  @Override public boolean hasValidArrival(UUID appointmentId, Instant tolerance) {
    return jdbc.sql("SELECT count(*) FROM wash_execution_evidence_projection WHERE appointment_id=:appointment AND arrived_at IS NOT NULL AND arrived_at <= :tolerance")
      .param("appointment",appointmentId).param("tolerance",Timestamps.toTimestamp(tolerance))
      .query(Long.class).single()>0;
  }
}

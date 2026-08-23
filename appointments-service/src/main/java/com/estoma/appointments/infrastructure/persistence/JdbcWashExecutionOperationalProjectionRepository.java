package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.WashExecutionOperationalProjectionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWashExecutionOperationalProjectionRepository implements WashExecutionOperationalProjectionRepository {
  private final JdbcClient jdbc;
  public JdbcWashExecutionOperationalProjectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public void upsertResourceUnavailability(ResourceUnavailabilityProjection p) {
    jdbc.sql("INSERT INTO resource_unavailability_projection (resource_unavailability_id,cabin_id,tank_id,disabled_at,restored_at,source_version,updated_at) VALUES (:id,:cabin,:tank,:disabled,:restored,:version,:updated) ON CONFLICT (resource_unavailability_id) DO UPDATE SET cabin_id=EXCLUDED.cabin_id,tank_id=EXCLUDED.tank_id,disabled_at=EXCLUDED.disabled_at,restored_at=EXCLUDED.restored_at,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at WHERE resource_unavailability_projection.source_version < EXCLUDED.source_version")
      .param("id",p.resourceUnavailabilityId()).param("cabin",p.cabinId()).param("tank",p.tankId())
      .param("disabled",Timestamps.toTimestamp(p.disabledAt())).param("restored",Timestamps.toTimestamp(p.restoredAt()))
      .param("version",p.sourceVersion()).param("updated",Timestamps.toTimestamp(p.updatedAt())).update();
  }

  @Override public void upsertCleaningPeriod(CleaningPeriodProjection p) {
    jdbc.sql("INSERT INTO cleaning_period_projection (cleaning_period_id,wash_execution_id,resource_assignment_id,cabin_id,cleaning_trigger,starts_at,expected_ends_at,completed_at,source_version,updated_at) VALUES (:id,:execution,:assignment,:cabin,:trigger,:starts,:ends,:completed,:version,:updated) ON CONFLICT (cleaning_period_id) DO UPDATE SET wash_execution_id=EXCLUDED.wash_execution_id,resource_assignment_id=EXCLUDED.resource_assignment_id,cabin_id=EXCLUDED.cabin_id,cleaning_trigger=EXCLUDED.cleaning_trigger,starts_at=EXCLUDED.starts_at,expected_ends_at=EXCLUDED.expected_ends_at,completed_at=EXCLUDED.completed_at,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at WHERE cleaning_period_projection.source_version < EXCLUDED.source_version")
      .param("id",p.cleaningPeriodId()).param("execution",p.washExecutionId()).param("assignment",p.resourceAssignmentId())
      .param("cabin",p.cabinId()).param("trigger",p.cleaningTrigger()).param("starts",Timestamps.toTimestamp(p.startsAt()))
      .param("ends",Timestamps.toTimestamp(p.expectedEndsAt())).param("completed",Timestamps.toTimestamp(p.completedAt()))
      .param("version",p.sourceVersion()).param("updated",Timestamps.toTimestamp(p.updatedAt())).update();
  }

  @Override public List<ResourceUnavailabilityProjection> findUnavailabilityOverlapping(Instant startsAt, Instant endsAt) {
    return jdbc.sql("SELECT resource_unavailability_id,cabin_id,tank_id,disabled_at,restored_at,source_version,updated_at FROM resource_unavailability_projection WHERE disabled_at < :ends AND (restored_at IS NULL OR restored_at > :starts)")
      .param("starts",Timestamps.toTimestamp(startsAt)).param("ends",Timestamps.toTimestamp(endsAt))
      .query((rs,row)->new ResourceUnavailabilityProjection(rs.getObject("resource_unavailability_id",UUID.class),rs.getObject("cabin_id",UUID.class),rs.getObject("tank_id",UUID.class),Timestamps.toInstant(rs,"disabled_at"),Timestamps.toInstant(rs,"restored_at"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"))).list();
  }

  @Override public List<CleaningPeriodProjection> findCleaningPeriodsOverlapping(Instant startsAt, Instant endsAt) {
    return jdbc.sql("SELECT cleaning_period_id,wash_execution_id,resource_assignment_id,cabin_id,cleaning_trigger,starts_at,expected_ends_at,completed_at,source_version,updated_at FROM cleaning_period_projection WHERE starts_at < :ends AND expected_ends_at > :starts")
      .param("starts",Timestamps.toTimestamp(startsAt)).param("ends",Timestamps.toTimestamp(endsAt))
      .query((rs,row)->new CleaningPeriodProjection(rs.getObject("cleaning_period_id",UUID.class),rs.getObject("wash_execution_id",UUID.class),rs.getObject("resource_assignment_id",UUID.class),rs.getObject("cabin_id",UUID.class),rs.getString("cleaning_trigger"),Timestamps.toInstant(rs,"starts_at"),Timestamps.toInstant(rs,"expected_ends_at"),Timestamps.toInstant(rs,"completed_at"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"))).list();
  }

  @Override public boolean reliable() {
    return jdbc.sql("SELECT COALESCE(bool_and(reliable),true) FROM (SELECT reliable FROM resource_unavailability_projection UNION ALL SELECT reliable FROM cleaning_period_projection) p")
      .query(Boolean.class).single();
  }
}

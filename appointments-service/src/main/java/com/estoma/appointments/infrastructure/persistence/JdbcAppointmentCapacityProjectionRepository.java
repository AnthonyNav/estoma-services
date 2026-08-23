package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
import com.estoma.appointments.domain.model.AppointmentType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAppointmentCapacityProjectionRepository implements AppointmentCapacityProjectionRepository {
  private final JdbcClient jdbc;
  public JdbcAppointmentCapacityProjectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override public CapacityProjection save(CapacityProjection p) {
    jdbc.sql("""
      INSERT INTO appointment_capacity_projections (appointment_time_slot_id,normal_capacity,journey_capacity,immunocompromised_capacity,normal_demand,journey_demand,immunocompromised_demand,version,calculated_at)
      VALUES (:slot,:normalCapacity,:journeyCapacity,:immunoCapacity,:normalDemand,:journeyDemand,:immunoDemand,:version,:calculatedAt)
      ON CONFLICT (appointment_time_slot_id) DO UPDATE SET normal_capacity=EXCLUDED.normal_capacity,journey_capacity=EXCLUDED.journey_capacity,immunocompromised_capacity=EXCLUDED.immunocompromised_capacity,normal_demand=EXCLUDED.normal_demand,journey_demand=EXCLUDED.journey_demand,immunocompromised_demand=EXCLUDED.immunocompromised_demand,version=EXCLUDED.version,calculated_at=EXCLUDED.calculated_at
      """)
      .param("slot", p.slotId()).param("normalCapacity", p.normalCapacity()).param("journeyCapacity", p.journeyCapacity()).param("immunoCapacity", p.immunocompromisedCapacity())
      .param("normalDemand", p.normalDemand()).param("journeyDemand", p.journeyDemand()).param("immunoDemand", p.immunocompromisedDemand()).param("version", p.version()).param("calculatedAt", Timestamps.toTimestamp(p.calculatedAt())).update();
    return p;
  }
  @Override public Optional<CapacityProjection> findBySlotId(UUID id) { return find(id, false); }
  @Override public Optional<CapacityProjection> findBySlotIdForUpdate(UUID id) { return find(id, true); }
  private Optional<CapacityProjection> find(UUID id, boolean lock) {
    return jdbc.sql("SELECT * FROM appointment_capacity_projections WHERE appointment_time_slot_id=:slot" + (lock ? " FOR UPDATE" : ""))
      .param("slot", id).query((rs, row) -> new CapacityProjection(rs.getObject("appointment_time_slot_id", UUID.class), rs.getInt("normal_capacity"), rs.getInt("journey_capacity"), rs.getInt("immunocompromised_capacity"), rs.getInt("normal_demand"), rs.getInt("journey_demand"), rs.getInt("immunocompromised_demand"), rs.getLong("version"), Timestamps.toInstant(rs, "calculated_at"))).optional();
  }
  @Override public boolean updateIfVersion(CapacityProjection p, long expectedVersion) {
    return jdbc.sql("UPDATE appointment_capacity_projections SET normal_capacity=:normalCapacity,journey_capacity=:journeyCapacity,immunocompromised_capacity=:immunoCapacity,normal_demand=:normalDemand,journey_demand=:journeyDemand,immunocompromised_demand=:immunoDemand,version=:version,calculated_at=:calculatedAt WHERE appointment_time_slot_id=:slot AND version=:expectedVersion")
      .param("normalCapacity", p.normalCapacity()).param("journeyCapacity", p.journeyCapacity()).param("immunoCapacity", p.immunocompromisedCapacity()).param("normalDemand", p.normalDemand()).param("journeyDemand", p.journeyDemand()).param("immunoDemand", p.immunocompromisedDemand()).param("version", p.version()).param("calculatedAt", Timestamps.toTimestamp(p.calculatedAt())).param("slot", p.slotId()).param("expectedVersion", expectedVersion).update() == 1;
  }

  @Override public boolean tryReserve(UUID slotId, AppointmentType type) {
    String demandUpdate = switch (type) {
      case IMMUNOCOMPROMISED -> "immunocompromised_demand=immunocompromised_demand+1";
      case NORMAL -> "normal_demand=CASE WHEN normal_demand < normal_capacity THEN normal_demand+1 ELSE normal_demand END,journey_demand=CASE WHEN normal_demand < normal_capacity THEN journey_demand ELSE journey_demand+1 END";
      case JOURNEY -> "journey_demand=CASE WHEN journey_demand < journey_capacity THEN journey_demand+1 ELSE journey_demand END,normal_demand=CASE WHEN journey_demand < journey_capacity THEN normal_demand ELSE normal_demand+1 END";
    };
    String available = switch (type) {
      case IMMUNOCOMPROMISED -> "immunocompromised_demand < immunocompromised_capacity AND normal_demand=0 AND journey_demand=0";
      case NORMAL -> "immunocompromised_demand=0 AND (normal_demand < normal_capacity OR journey_demand < journey_capacity)";
      case JOURNEY -> "immunocompromised_demand=0 AND (journey_demand < journey_capacity OR normal_demand < normal_capacity)";
    };
    return jdbc.sql("UPDATE appointment_capacity_projections SET " + demandUpdate + ",version=version+1,calculated_at=now() WHERE appointment_time_slot_id=:slot AND " + available)
        .param("slot", slotId).update() == 1;
  }
}

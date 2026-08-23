package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.AppointmentCapacityProjectionRepository;
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
      INSERT INTO appointment_capacity_projections
        (appointment_time_slot_id,normal_capacity,journey_capacity,immunocompromised_capacity,version,calculated_at)
      VALUES (:slot,:normalCapacity,:journeyCapacity,:immunoCapacity,:version,:calculatedAt)
      ON CONFLICT (appointment_time_slot_id) DO UPDATE SET
        normal_capacity=EXCLUDED.normal_capacity,
        journey_capacity=EXCLUDED.journey_capacity,
        immunocompromised_capacity=EXCLUDED.immunocompromised_capacity,
        version=EXCLUDED.version,
        calculated_at=EXCLUDED.calculated_at
      """)
      .param("slot", p.slotId())
      .param("normalCapacity", p.normalCapacity())
      .param("journeyCapacity", p.journeyCapacity())
      .param("immunoCapacity", p.immunocompromisedCapacity())
      .param("version", p.version())
      .param("calculatedAt", Timestamps.toTimestamp(p.calculatedAt()))
      .update();
    return p;
  }

  @Override public Optional<CapacityProjection> findBySlotId(UUID id) { return find(id, false); }
  @Override public Optional<CapacityProjection> findBySlotIdForUpdate(UUID id) { return find(id, true); }

  private Optional<CapacityProjection> find(UUID id, boolean lock) {
    return jdbc.sql("SELECT appointment_time_slot_id,normal_capacity,journey_capacity,immunocompromised_capacity,version,calculated_at FROM appointment_capacity_projections WHERE appointment_time_slot_id=:slot" + (lock ? " FOR UPDATE" : ""))
      .param("slot", id)
      .query((rs, row) -> new CapacityProjection(
          rs.getObject("appointment_time_slot_id", UUID.class),
          rs.getInt("normal_capacity"),
          rs.getInt("journey_capacity"),
          rs.getInt("immunocompromised_capacity"),
          rs.getLong("version"),
          Timestamps.toInstant(rs, "calculated_at")))
      .optional();
  }

  @Override public boolean updateIfVersion(CapacityProjection p, long expectedVersion) {
    return jdbc.sql("""
      UPDATE appointment_capacity_projections SET
        normal_capacity=:normalCapacity,
        journey_capacity=:journeyCapacity,
        immunocompromised_capacity=:immunoCapacity,
        version=:version,
        calculated_at=:calculatedAt
      WHERE appointment_time_slot_id=:slot AND version=:expectedVersion
      """)
      .param("normalCapacity", p.normalCapacity())
      .param("journeyCapacity", p.journeyCapacity())
      .param("immunoCapacity", p.immunocompromisedCapacity())
      .param("version", p.version())
      .param("calculatedAt", Timestamps.toTimestamp(p.calculatedAt()))
      .param("slot", p.slotId())
      .param("expectedVersion", expectedVersion)
      .update() == 1;
  }
}

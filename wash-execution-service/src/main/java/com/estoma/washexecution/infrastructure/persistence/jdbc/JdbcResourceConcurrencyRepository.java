package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.ResourceConcurrencyRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcResourceConcurrencyRepository implements ResourceConcurrencyRepository {
  private final JdbcTemplate jdbc;

  public JdbcResourceConcurrencyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockCapacityDecision() {
    // A negative decision must also fence inserts and resources that are currently ineligible.
    // EXCLUSIVE also conflicts with SELECT FOR UPDATE from an allocator that has not written yet.
    // NOWAIT avoids queuing behind eligibility writers. Bound later row-lock waits as well.
    // Lock conflicts escape as transient technical failures: rollback, then broker retry.
    jdbc.execute("SET LOCAL lock_timeout = '500ms'");
    jdbc.execute("""
        LOCK TABLE appointment_projection, appointment_time_slot_projection,
          cabin_projection, tank_projection, resource_priority_projection,
          administrative_closure_projection, resource_assignments,
          resource_unavailabilities, cleaning_periods, projection_recovery_state
        IN EXCLUSIVE MODE NOWAIT
        """);
  }

  @Override
  public void lockCabin(UUID cabinId) {
    UUID lockedCabin = jdbc.queryForObject(
        "SELECT cabin_id FROM cabin_projection WHERE cabin_id = ? FOR UPDATE", UUID.class, cabinId);
    if (lockedCabin == null) throw new IllegalStateException("CABIN_NOT_FOUND");
  }

  @Override
  public void lockTank(UUID cabinId, UUID tankId) {
    UUID lockedTank = jdbc.queryForObject(
        "SELECT tank_id FROM tank_projection WHERE tank_id = ? AND cabin_id = ? FOR UPDATE",
        UUID.class, tankId, cabinId);
    if (lockedTank == null) throw new IllegalStateException("TANK_NOT_FOUND");
  }
}

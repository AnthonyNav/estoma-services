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

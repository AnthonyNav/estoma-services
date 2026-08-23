package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.ResourceUnavailabilityRepository;
import com.estoma.washexecution.domain.execution.ResourceUnavailability;
import com.estoma.washexecution.domain.execution.UnavailabilityCauseType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcResourceUnavailabilityRepository implements ResourceUnavailabilityRepository {
  private final JdbcTemplate jdbc;

  public JdbcResourceUnavailabilityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public Optional<ResourceUnavailability> findById(UUID id) {
    return jdbc.query("SELECT * FROM resource_unavailabilities WHERE resource_unavailability_id=?", this::map, id).stream().findFirst();
  }

  @Override
  public List<ResourceUnavailability> findActiveForResource(UUID cabinId, UUID tankId) {
    return jdbc.query("""
        SELECT * FROM resource_unavailabilities
        WHERE restored_at IS NULL
          AND ((CAST(? AS uuid) IS NOT NULL AND cabin_id=CAST(? AS uuid))
            OR (CAST(? AS uuid) IS NOT NULL AND tank_id=CAST(? AS uuid)))
        ORDER BY disabled_at,resource_unavailability_id
        """, this::map, cabinId, cabinId, tankId, tankId);
  }

  @Override
  public void save(ResourceUnavailability value, int expectedVersion) {
    Instant now = Instant.now();
    if (expectedVersion == 0) {
      jdbc.update("""
          INSERT INTO resource_unavailabilities (
            resource_unavailability_id,detected_during_wash_execution_id,cabin_id,tank_id,cause_type,
            reason,disabled_by_account_id,disabled_at,restored_by_account_id,restored_at,resolution,
            version,created_at,updated_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """, value.resourceUnavailabilityId(), value.detectedDuringWashExecutionId(), value.cabinId(),
          value.tankId(), value.causeType().name(), value.reason(), value.disabledByAccountId(),
          JdbcTemporal.timestamp(value.disabledAt()), value.restoredByAccountId(),
          JdbcTemporal.timestamp(value.restoredAt()), value.resolution(), value.version(),
          JdbcTemporal.timestamp(now), JdbcTemporal.timestamp(now));
      return;
    }
    int updated = jdbc.update("""
        UPDATE resource_unavailabilities
        SET restored_by_account_id=?,restored_at=?,resolution=?,version=?,updated_at=?
        WHERE resource_unavailability_id=? AND version=?
        """, value.restoredByAccountId(), JdbcTemporal.timestamp(value.restoredAt()), value.resolution(),
        value.version(), JdbcTemporal.timestamp(now), value.resourceUnavailabilityId(), expectedVersion);
    if (updated != 1) throw new IllegalStateException("VERSION_CONFLICT");
  }

  private ResourceUnavailability map(ResultSet rs, int row) throws SQLException {
    return ResourceUnavailability.reconstitute(
        rs.getObject("resource_unavailability_id", UUID.class),
        rs.getObject("detected_during_wash_execution_id", UUID.class),
        rs.getObject("cabin_id", UUID.class), rs.getObject("tank_id", UUID.class),
        UnavailabilityCauseType.valueOf(rs.getString("cause_type")), rs.getString("reason"),
        rs.getObject("disabled_by_account_id", UUID.class), rs.getTimestamp("disabled_at").toInstant(),
        rs.getObject("restored_by_account_id", UUID.class), instant(rs, "restored_at"),
        rs.getString("resolution"), rs.getInt("version"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}

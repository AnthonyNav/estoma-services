package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.CleaningPeriodRepository;
import com.estoma.washexecution.domain.execution.CleaningPeriod;
import com.estoma.washexecution.domain.execution.CleaningTrigger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCleaningPeriodRepository implements CleaningPeriodRepository {
  private final JdbcTemplate jdbc;

  public JdbcCleaningPeriodRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public Optional<CleaningPeriod> findByAssignmentId(UUID id) {
    return jdbc.query("SELECT * FROM cleaning_periods WHERE resource_assignment_id=?", this::map, id).stream().findFirst();
  }

  @Override
  public List<CleaningPeriod> findExpiredForUpdate(Instant now) {
    return jdbc.query(
        "SELECT * FROM cleaning_periods WHERE completed_at IS NULL AND expected_ends_at <= ? ORDER BY expected_ends_at,cleaning_period_id FOR UPDATE SKIP LOCKED",
        this::map, now);
  }

  @Override
  public List<CleaningPeriod> findOverlapping(UUID cabinId, Instant start, Instant end) {
    return jdbc.query(
        "SELECT * FROM cleaning_periods WHERE cabin_id=? AND starts_at < ? AND expected_ends_at > ?",
        this::map, cabinId, end, start);
  }

  @Override
  public void save(CleaningPeriod p, int expectedVersion) {
    Instant now = Instant.now();
    if (expectedVersion == 0) {
      jdbc.update("""
          INSERT INTO cleaning_periods (
            cleaning_period_id,wash_execution_id,resource_assignment_id,cabin_id,cleaning_trigger,
            starts_at,expected_ends_at,completed_by_account_id,completed_at,version,created_at,updated_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
          """, p.cleaningPeriodId(), p.washExecutionId(), p.resourceAssignmentId(), p.cabinId(),
          p.cleaningTrigger().name(), p.startsAt(), p.expectedEndsAt(), null, p.completedAt(), p.version(), now, now);
      return;
    }
    int updated = jdbc.update("""
        UPDATE cleaning_periods
        SET completed_by_account_id=NULL,completed_at=?,version=?,updated_at=?
        WHERE cleaning_period_id=? AND version=?
        """, p.completedAt(), p.version(), now, p.cleaningPeriodId(), expectedVersion);
    if (updated != 1) throw new IllegalStateException("VERSION_CONFLICT");
  }

  private CleaningPeriod map(ResultSet rs, int row) throws SQLException {
    return CleaningPeriod.reconstitute(
        rs.getObject("cleaning_period_id", UUID.class), rs.getObject("wash_execution_id", UUID.class),
        rs.getObject("resource_assignment_id", UUID.class), rs.getObject("cabin_id", UUID.class),
        CleaningTrigger.valueOf(rs.getString("cleaning_trigger")), rs.getTimestamp("starts_at").toInstant(),
        rs.getTimestamp("expected_ends_at").toInstant(), rs.getObject("completed_by_account_id", UUID.class),
        instant(rs, "completed_at"), rs.getInt("version"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}

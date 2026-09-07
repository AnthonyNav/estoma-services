package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.WashExecutionRepository;
import com.estoma.washexecution.application.port.OptimisticConcurrencyException;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.CancellationType;
import com.estoma.washexecution.domain.execution.ExitMaterials;
import com.estoma.washexecution.domain.execution.WashExecution;
import com.estoma.washexecution.domain.execution.WashExecutionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWashExecutionRepository implements WashExecutionRepository {

  private final JdbcTemplate jdbc;

  public JdbcWashExecutionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<WashExecution> findById(UUID id) {
    return query("SELECT * FROM wash_executions WHERE wash_execution_id = ?", id);
  }

  @Override
  public Optional<WashExecution> findByAppointmentId(UUID appointmentId) {
    return query("SELECT * FROM wash_executions WHERE appointment_id = ?", appointmentId);
  }

  private Optional<WashExecution> query(String sql, UUID id) {
    return jdbc.query(sql, this::map, id).stream().findFirst();
  }

  @Override
  public void save(WashExecution e, int expectedVersion) {
    Instant now = Instant.now();
    if (expectedVersion == 0) {
      jdbc.update("""
          INSERT INTO wash_executions (wash_execution_id, appointment_id, appointment_type, status, arrived_at,
            started_at, exit_submitted_by_account_id, exit_submitted_at, package_count,
            green_paper_cassette_8_count, green_paper_cassette_10_count, witness_tape_portion_count,
            completed_by_account_id, completed_at, cancellation_type, cancellation_subreason, cancellation_reason,
            cancelled_by_account_id, cancelled_at, version, created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, e.washExecutionId(), e.appointmentId(), e.appointmentType().name(), e.status().name(),
          JdbcTemporal.timestamp(e.arrivedAt()), JdbcTemporal.timestamp(e.startedAt()),
          e.exitSubmittedByAccountId(), JdbcTemporal.timestamp(e.exitSubmittedAt()), value(e.exitMaterials(), 0),
          value(e.exitMaterials(), 1), value(e.exitMaterials(), 2), value(e.exitMaterials(), 3),
          e.completedByAccountId(), JdbcTemporal.timestamp(e.completedAt()), name(e.cancellationType()),
          e.cancellationSubreason(), e.cancellationReason(), e.cancelledByAccountId(),
          JdbcTemporal.timestamp(e.cancelledAt()), e.version(), JdbcTemporal.timestamp(now),
          JdbcTemporal.timestamp(now));
      return;
    }
    int updated = jdbc.update("""
        UPDATE wash_executions SET status=?, started_at=?, exit_submitted_by_account_id=?, exit_submitted_at=?,
          package_count=?, green_paper_cassette_8_count=?, green_paper_cassette_10_count=?, witness_tape_portion_count=?,
          completed_by_account_id=?, completed_at=?, cancellation_type=?, cancellation_subreason=?, cancellation_reason=?,
          cancelled_by_account_id=?, cancelled_at=?, version=?, updated_at=?
        WHERE wash_execution_id=? AND version=?
        """, e.status().name(), JdbcTemporal.timestamp(e.startedAt()), e.exitSubmittedByAccountId(),
        JdbcTemporal.timestamp(e.exitSubmittedAt()),
        value(e.exitMaterials(), 0), value(e.exitMaterials(), 1), value(e.exitMaterials(), 2), value(e.exitMaterials(), 3),
        e.completedByAccountId(), JdbcTemporal.timestamp(e.completedAt()), name(e.cancellationType()),
        e.cancellationSubreason(), e.cancellationReason(), e.cancelledByAccountId(),
        JdbcTemporal.timestamp(e.cancelledAt()), e.version(), JdbcTemporal.timestamp(now),
        e.washExecutionId(), expectedVersion);
    if (updated != 1) throw new OptimisticConcurrencyException();
  }

  private WashExecution map(ResultSet rs, int row) throws SQLException {
    return WashExecution.reconstitute(
        rs.getObject("wash_execution_id", UUID.class), rs.getObject("appointment_id", UUID.class),
        AppointmentType.valueOf(rs.getString("appointment_type")), rs.getTimestamp("arrived_at").toInstant(),
        instant(rs, "started_at"), rs.getObject("exit_submitted_by_account_id", UUID.class), instant(rs, "exit_submitted_at"),
        materials(rs), rs.getObject("completed_by_account_id", UUID.class), instant(rs, "completed_at"),
        optionalEnum(rs.getString("cancellation_type"), CancellationType.class), rs.getString("cancellation_subreason"),
        rs.getString("cancellation_reason"), rs.getObject("cancelled_by_account_id", UUID.class), instant(rs, "cancelled_at"),
        WashExecutionStatus.valueOf(rs.getString("status")), rs.getInt("version"));
  }

  private static ExitMaterials materials(ResultSet rs) throws SQLException {
    Integer p = (Integer) rs.getObject("package_count");
    Integer c8 = (Integer) rs.getObject("green_paper_cassette_8_count");
    Integer c10 = (Integer) rs.getObject("green_paper_cassette_10_count");
    Integer tape = (Integer) rs.getObject("witness_tape_portion_count");
    return p == null && c8 == null && c10 == null && tape == null ? null : new ExitMaterials(p, c8, c10, tape);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static <E extends Enum<E>> E optionalEnum(String value, Class<E> type) {
    return value == null ? null : Enum.valueOf(type, value);
  }

  private static String name(Enum<?> value) { return value == null ? null : value.name(); }

  private static Integer value(ExitMaterials materials, int index) {
    if (materials == null) return null;
    return switch (index) {
      case 0 -> materials.packageCount();
      case 1 -> materials.greenPaperCassette8Count();
      case 2 -> materials.greenPaperCassette10Count();
      default -> materials.witnessTapePortionCount();
    };
  }
}

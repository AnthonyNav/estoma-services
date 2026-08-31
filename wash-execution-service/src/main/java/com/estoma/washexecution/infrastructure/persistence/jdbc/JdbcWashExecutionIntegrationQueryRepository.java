package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.WashExecutionIntegrationQueryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWashExecutionIntegrationQueryRepository
    implements WashExecutionIntegrationQueryRepository {
  private static final String EXECUTION_SELECT = """
      SELECT w.*,
             CASE WHEN ev.decision='AUTHORIZED' THEN ev.decided_at END AS entry_authorized_at,
             CASE WHEN ev.decision='REJECTED' THEN ev.decided_at END AS rejected_at,
             CASE WHEN ev.decision='REJECTED' THEN ev.rejection_reason END AS rejection_reason,
             CASE WHEN ev.decision='REJECTED' THEN ev.decided_by_account_id END AS rejected_by_account_id
      FROM wash_executions w
      LEFT JOIN entry_validations ev ON ev.wash_execution_id=w.wash_execution_id
      """;

  private final JdbcTemplate jdbc;

  public JdbcWashExecutionIntegrationQueryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public EntityContext get(UUID appointmentId) {
    var execution = jdbc.query(EXECUTION_SELECT + " WHERE w.appointment_id=?",
        this::execution, appointmentId).stream().findFirst().orElse(null);
    if (execution == null) {
      return new EntityContext(appointmentId, null, List.of(), List.of());
    }
    return new EntityContext(
        appointmentId,
        execution,
        unavailabilitiesForExecutions(List.of(execution.washExecutionId())),
        cleanings(List.of(execution.washExecutionId())));
  }

  @Override
  public Page list(LocalDate date, int pageSize, String token) {
    UUID after = token == null || token.isBlank() ? null : UUID.fromString(token);
    String sql = """
        SELECT w.*,
               CASE WHEN ev.decision='AUTHORIZED' THEN ev.decided_at END AS entry_authorized_at,
               CASE WHEN ev.decision='REJECTED' THEN ev.decided_at END AS rejected_at,
               CASE WHEN ev.decision='REJECTED' THEN ev.rejection_reason END AS rejection_reason,
               CASE WHEN ev.decision='REJECTED' THEN ev.decided_by_account_id END AS rejected_by_account_id
        FROM wash_executions w
        JOIN appointment_projection a ON a.appointment_id=w.appointment_id
        JOIN appointment_time_slot_projection s
          ON s.appointment_time_slot_id=a.appointment_time_slot_id
        LEFT JOIN entry_validations ev ON ev.wash_execution_id=w.wash_execution_id
        WHERE s.service_date=? %s
        ORDER BY w.appointment_id
        LIMIT ?
        """.formatted(after == null ? "" : "AND w.appointment_id > ?");

    List<ExecutionSnapshot> rows = after == null
        ? jdbc.query(sql, this::execution, date, pageSize + 1)
        : jdbc.query(sql, this::execution, date, after, pageSize + 1);
    boolean more = rows.size() > pageSize;
    List<ExecutionSnapshot> page = more ? rows.subList(0, pageSize) : rows;
    List<UUID> ids = page.stream().map(ExecutionSnapshot::washExecutionId).toList();
    String next = more ? page.getLast().appointmentId().toString() : null;

    return new Page(
        date,
        List.copyOf(page),
        activeUnavailabilities(),
        cleanings(ids),
        next,
        Instant.now());
  }

  private List<UnavailabilitySnapshot> activeUnavailabilities() {
    return jdbc.query("""
        SELECT *
        FROM resource_unavailabilities
        WHERE restored_at IS NULL
        ORDER BY disabled_at,resource_unavailability_id
        """, this::unavailability);
  }

  private List<UnavailabilitySnapshot> unavailabilitiesForExecutions(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    Map<UUID, UnavailabilitySnapshot> result = new LinkedHashMap<>();
    for (UUID id : ids) {
      jdbc.query("""
          SELECT DISTINCT u.*
          FROM resource_unavailabilities u
          LEFT JOIN resource_unavailability_affected_executions a
            ON a.resource_unavailability_id=u.resource_unavailability_id
          WHERE u.detected_during_wash_execution_id=? OR a.wash_execution_id=?
          """, (rs, row) -> {
            var value = unavailability(rs, row);
            result.put(value.resourceUnavailabilityId(), value);
            return value;
          }, id, id);
    }
    return List.copyOf(result.values());
  }

  private List<CleaningSnapshot> cleanings(List<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    List<CleaningSnapshot> result = new ArrayList<>();
    for (UUID id : ids) {
      result.addAll(jdbc.query("""
          SELECT * FROM cleaning_periods
          WHERE wash_execution_id=?
          ORDER BY starts_at,cleaning_period_id
          """, this::cleaning, id));
    }
    return List.copyOf(result);
  }

  private ExecutionSnapshot execution(ResultSet rs, int row) throws SQLException {
    return new ExecutionSnapshot(
        rs.getObject("wash_execution_id", UUID.class),
        rs.getObject("appointment_id", UUID.class),
        rs.getString("appointment_type"),
        rs.getString("status"),
        rs.getTimestamp("arrived_at").toInstant(),
        instant(rs, "started_at"),
        instant(rs, "entry_authorized_at"),
        instant(rs, "rejected_at"),
        rs.getString("rejection_reason"),
        rs.getObject("rejected_by_account_id", UUID.class),
        instant(rs, "completed_at"),
        rs.getObject("completed_by_account_id", UUID.class),
        instant(rs, "cancelled_at"),
        rs.getString("cancellation_type"),
        rs.getString("cancellation_subreason"),
        rs.getString("cancellation_reason"),
        rs.getObject("cancelled_by_account_id", UUID.class),
        rs.getInt("version"));
  }

  private UnavailabilitySnapshot unavailability(ResultSet rs, int row) throws SQLException {
    return new UnavailabilitySnapshot(
        rs.getObject("resource_unavailability_id", UUID.class),
        rs.getObject("cabin_id", UUID.class),
        rs.getObject("tank_id", UUID.class),
        rs.getTimestamp("disabled_at").toInstant(),
        instant(rs, "restored_at"),
        rs.getInt("version"));
  }

  private CleaningSnapshot cleaning(ResultSet rs, int row) throws SQLException {
    return new CleaningSnapshot(
        rs.getObject("cleaning_period_id", UUID.class),
        rs.getObject("wash_execution_id", UUID.class),
        rs.getObject("resource_assignment_id", UUID.class),
        rs.getObject("cabin_id", UUID.class),
        rs.getString("cleaning_trigger"),
        rs.getTimestamp("starts_at").toInstant(),
        rs.getTimestamp("expected_ends_at").toInstant(),
        instant(rs, "completed_at"),
        rs.getInt("version"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}

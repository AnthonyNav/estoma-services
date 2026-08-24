package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.ResourceAssignmentRepository;
import com.estoma.washexecution.domain.execution.AssignmentType;
import com.estoma.washexecution.domain.execution.ReleaseReason;
import com.estoma.washexecution.domain.execution.ResourceAssignment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcResourceAssignmentRepository implements ResourceAssignmentRepository {
  private final JdbcTemplate jdbc;

  public JdbcResourceAssignmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public Optional<ResourceAssignment> findActiveByExecutionId(UUID id) {
    return jdbc.query("SELECT * FROM resource_assignments WHERE wash_execution_id = ? AND released_at IS NULL", this::map, id).stream().findFirst();
  }

  @Override
  public List<ResourceAssignment> findByExecutionId(UUID id) {
    return jdbc.query("SELECT * FROM resource_assignments WHERE wash_execution_id = ? ORDER BY assigned_at", this::map, id);
  }

  @Override
  public List<ResourceAssignment> findActiveByResource(UUID cabinId, UUID tankId) {
    return jdbc.query("""
        SELECT * FROM resource_assignments
        WHERE released_at IS NULL
          AND (CAST(? AS uuid) IS NULL OR cabin_id = CAST(? AS uuid))
          AND (CAST(? AS uuid) IS NULL OR tank_id = CAST(? AS uuid))
        """, this::map, cabinId, cabinId, tankId, tankId);
  }

  @Override
  public int countActiveByTankAndSlot(UUID tankId, UUID slotId) {
    Integer count = jdbc.queryForObject(
        "SELECT count(*) FROM resource_assignments WHERE tank_id = ? AND appointment_time_slot_id = ? AND released_at IS NULL",
        Integer.class, tankId, slotId);
    return count == null ? 0 : count;
  }

  @Override
  public void save(ResourceAssignment a) {
    jdbc.update("""
        INSERT INTO resource_assignments (resource_assignment_id, wash_execution_id, appointment_time_slot_id, cabin_id, tank_id,
          assignment_type, assigned_by_account_id, assigned_at, released_by_account_id, released_at, release_reason, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, a.resourceAssignmentId(), a.washExecutionId(), a.appointmentTimeSlotId(), a.cabinId(), a.tankId(),
        a.assignmentType().name(), a.assignedByAccountId(), JdbcTemporal.timestamp(a.assignedAt()),
        a.releasedByAccountId(), JdbcTemporal.timestamp(a.releasedAt()), name(a.releaseReason()),
        JdbcTemporal.timestamp(Instant.now()));
  }

  @Override
  public void release(ResourceAssignment a) {
    int updated = jdbc.update(
        "UPDATE resource_assignments SET released_by_account_id=?, released_at=?, release_reason=? WHERE resource_assignment_id=? AND released_at IS NULL",
        a.releasedByAccountId(), JdbcTemporal.timestamp(a.releasedAt()), name(a.releaseReason()),
        a.resourceAssignmentId());
    if (updated != 1) throw new IllegalStateException("RESOURCE_ASSIGNMENT_RELEASE_CONFLICT");
  }

  private ResourceAssignment map(ResultSet rs, int row) throws SQLException {
    return ResourceAssignment.reconstitute(
        rs.getObject("resource_assignment_id", UUID.class), rs.getObject("wash_execution_id", UUID.class),
        rs.getObject("appointment_time_slot_id", UUID.class), rs.getObject("cabin_id", UUID.class),
        rs.getObject("tank_id", UUID.class), AssignmentType.valueOf(rs.getString("assignment_type")),
        rs.getObject("assigned_by_account_id", UUID.class), rs.getTimestamp("assigned_at").toInstant(),
        rs.getObject("released_by_account_id", UUID.class), instant(rs, "released_at"),
        optionalEnum(rs.getString("release_reason"), ReleaseReason.class));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static <E extends Enum<E>> E optionalEnum(String value, Class<E> type) {
    return value == null ? null : Enum.valueOf(type, value);
  }

  private static String name(Enum<?> value) { return value == null ? null : value.name(); }
}

package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.AffectedExecutionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAffectedExecutionRepository implements AffectedExecutionRepository {
  private final JdbcTemplate jdbc;

  public JdbcAffectedExecutionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void add(UUID unavailabilityId, UUID executionId, UUID assignmentId, Instant affectedAt) {
    jdbc.update("""
        INSERT INTO resource_unavailability_affected_executions (
          resource_unavailability_id,wash_execution_id,resource_assignment_id,affected_at,created_at)
        VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
        """, unavailabilityId, executionId, assignmentId, JdbcTemporal.timestamp(affectedAt),
        JdbcTemporal.timestamp(Instant.now()));
  }

  @Override
  public List<AffectedExecution> findByUnavailabilityId(UUID id) {
    return jdbc.query("""
        SELECT resource_unavailability_id,wash_execution_id,resource_assignment_id,affected_at
        FROM resource_unavailability_affected_executions
        WHERE resource_unavailability_id=?
        ORDER BY affected_at,wash_execution_id,resource_assignment_id
        """, (rs, row) -> new AffectedExecution(
            rs.getObject("resource_unavailability_id", UUID.class),
            rs.getObject("wash_execution_id", UUID.class),
            rs.getObject("resource_assignment_id", UUID.class),
            rs.getTimestamp("affected_at").toInstant()), id);
  }
}

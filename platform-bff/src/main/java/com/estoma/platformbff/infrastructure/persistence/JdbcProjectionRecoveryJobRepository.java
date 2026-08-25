package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.ProjectionRecoveryJobRepository;
import com.estoma.platformbff.domain.projection.RecoveryJob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectionRecoveryJobRepository implements ProjectionRecoveryJobRepository {
  private final JdbcClient jdbc;

  public JdbcProjectionRecoveryJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(RecoveryJob job) {
    jdbc.sql("""
        INSERT INTO projection_recovery_jobs (
          recovery_job_id, projection_name, mode, entity_id, generation,
          owner_logical_component_id, query_type, operation_id, cursor_value,
          status, started_at, updated_at, completed_at, last_error_code, current_query_message_id
        ) VALUES (:recoveryJobId, :projectionName, :mode, :entityId, :generation,
          :ownerLogicalComponentId, :queryType, :operationId, :cursorValue,
          :status, :startedAt, :updatedAt, :completedAt, :lastErrorCode, :currentQueryMessageId)
        """)
        .param("recoveryJobId", job.recoveryJobId())
        .param("projectionName", job.projectionName())
        .param("mode", job.mode())
        .param("entityId", job.entityId())
        .param("generation", job.generation())
        .param("ownerLogicalComponentId", job.ownerLogicalComponentId())
        .param("queryType", job.queryType())
        .param("operationId", job.operationId())
        .param("cursorValue", job.cursor())
        .param("status", job.status())
        .param("startedAt", JdbcTimes.timestamp(job.startedAt()))
        .param("updatedAt", JdbcTimes.timestamp(job.updatedAt()))
        .param("completedAt", JdbcTimes.timestamp(job.completedAt()))
        .param("lastErrorCode", job.lastErrorCode())
        .param("currentQueryMessageId", job.currentQueryMessageId())
        .update();
  }

  @Override
  public void update(RecoveryJob job) {
    jdbc.sql("""
        UPDATE projection_recovery_jobs SET cursor_value = :cursorValue, status = :status,
          updated_at = :updatedAt, completed_at = :completedAt, last_error_code = :lastErrorCode,
          current_query_message_id = :currentQueryMessageId
        WHERE recovery_job_id = :recoveryJobId AND generation = :generation
        """)
        .param("cursorValue", job.cursor()).param("status", job.status())
        .param("updatedAt", JdbcTimes.timestamp(job.updatedAt())).param("completedAt", JdbcTimes.timestamp(job.completedAt()))
        .param("lastErrorCode", job.lastErrorCode()).param("currentQueryMessageId", job.currentQueryMessageId())
        .param("recoveryJobId", job.recoveryJobId()).param("generation", job.generation()).update();
  }

  @Override
  public long nextGeneration(String projectionName) {
    return jdbc.sql("SELECT COALESCE(MAX(generation), 0) + 1 FROM projection_recovery_jobs WHERE projection_name = :projectionName")
        .param("projectionName", projectionName).query(Long.class).single();
  }

  @Override
  public Optional<RecoveryJob> findById(UUID recoveryJobId) {
    return jdbc.sql("SELECT * FROM projection_recovery_jobs WHERE recovery_job_id = :recoveryJobId")
        .param("recoveryJobId", recoveryJobId)
        .query((rs, rowNum) -> map(rs))
        .optional();
  }

  private static RecoveryJob map(ResultSet rs) throws SQLException {
    return new RecoveryJob(rs.getObject("recovery_job_id", UUID.class),
        rs.getString("projection_name"), rs.getString("mode"),
        rs.getObject("entity_id", UUID.class), rs.getLong("generation"),
        rs.getString("owner_logical_component_id"), rs.getString("query_type"),
        rs.getObject("operation_id", UUID.class), rs.getString("cursor_value"),
        rs.getString("status"), JdbcTimes.instant(rs, "started_at"), JdbcTimes.instant(rs, "updated_at"),
        JdbcTimes.instant(rs, "completed_at"), rs.getString("last_error_code"),
        rs.getObject("current_query_message_id", UUID.class));
  }
}

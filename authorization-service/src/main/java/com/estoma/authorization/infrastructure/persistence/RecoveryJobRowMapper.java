package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository.RecoveryJob;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class RecoveryJobRowMapper implements RowMapper<RecoveryJob> {

  static final RecoveryJobRowMapper INSTANCE = new RecoveryJobRowMapper();

  private RecoveryJobRowMapper() {}

  @Override
  public RecoveryJob mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new RecoveryJob(
        rs.getObject("recovery_job_id", java.util.UUID.class),
        rs.getString("projection_type"),
        rs.getString("scope"),
        rs.getString("entity_key"),
        rs.getString("status"),
        rs.getString("cursor"),
        rs.getInt("query_attempt"),
        rs.getString("current_query_type"),
        rs.getObject("current_query_message_id", java.util.UUID.class),
        rs.getObject("current_operation_id", java.util.UUID.class),
        rs.getObject("current_correlation_id", java.util.UUID.class),
        rs.getInt("attempts"),
        rs.getString("last_error_code"),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"),
        Timestamps.toInstant(rs, "completed_at"),
        rs.getObject("claim_token", java.util.UUID.class),
        Timestamps.toInstant(rs, "lease_until"));
  }
}

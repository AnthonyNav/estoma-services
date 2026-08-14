package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.CommandIdempotencyRepository.IdempotencyRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class IdempotencyRowMapper implements RowMapper<IdempotencyRecord> {

  static final IdempotencyRowMapper INSTANCE = new IdempotencyRowMapper();

  private IdempotencyRowMapper() {}

  @Override
  public IdempotencyRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new IdempotencyRecord(
        rs.getString("producer"),
        rs.getString("operation_type"),
        rs.getString("idempotency_key"),
        rs.getString("request_fingerprint"),
        rs.getString("status"),
        rs.getString("result_status"),
        rs.getString("result_data"),
        rs.getString("error_code"),
        rs.getString("error_details"),
        rs.getObject("aggregate_id", java.util.UUID.class),
        (Integer) rs.getObject("aggregate_version"),
        rs.getObject("import_id", java.util.UUID.class),
        rs.getObject("plan_item_id", java.util.UUID.class),
        (Integer) rs.getObject("execution_generation"),
        rs.getString("pending_reason"),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "completed_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}

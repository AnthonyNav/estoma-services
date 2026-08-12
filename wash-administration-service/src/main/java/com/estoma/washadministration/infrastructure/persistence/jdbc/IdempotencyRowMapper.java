package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.CommandIdempotencyRepository.IdempotencyRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
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
        rs.getObject("aggregate_id", UUID.class),
        (Integer) rs.getObject("aggregate_version"),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "completed_at"));
  }
}

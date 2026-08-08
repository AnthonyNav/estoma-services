package com.estoma.users.persistence;

import com.estoma.users.application.IdempotencyRecord;
import com.estoma.users.application.IdempotencyStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** Maps one row of {@code idempotency_records} back into an {@link IdempotencyRecord}. */
final class IdempotencyRowMapper implements RowMapper<IdempotencyRecord> {

  static final IdempotencyRowMapper INSTANCE = new IdempotencyRowMapper();

  private IdempotencyRowMapper() {}

  @Override
  public IdempotencyRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new IdempotencyRecord(
        rs.getString("producer"),
        rs.getString("idempotency_key"),
        rs.getString("message_type"),
        rs.getObject("aggregate_id", UUID.class),
        rs.getString("request_fingerprint"),
        rs.getObject("claimed_by_message_id", UUID.class),
        IdempotencyStatus.valueOf(rs.getString("terminal_status")),
        (Integer) rs.getObject("aggregate_version"),
        rs.getString("error_code"),
        toInstant(rs, "created_at"),
        toInstant(rs, "completed_at"));
  }

  private static java.time.Instant toInstant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}

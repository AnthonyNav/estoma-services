package com.estoma.users.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** Maps one row of {@code outbox_messages} back into an {@link OutboxRecord}. */
final class OutboxRowMapper implements RowMapper<OutboxRecord> {

  static final OutboxRowMapper INSTANCE = new OutboxRowMapper();

  private OutboxRowMapper() {}

  @Override
  public OutboxRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new OutboxRecord(
        rs.getObject("message_id", UUID.class),
        rs.getString("message_type"),
        rs.getInt("message_version"),
        rs.getString("message_kind"),
        rs.getObject("aggregate_id", UUID.class),
        (Integer) rs.getObject("aggregate_version"),
        rs.getObject("correlation_id", UUID.class),
        rs.getObject("causation_id", UUID.class),
        rs.getString("payload"),
        rs.getString("metadata"),
        OutboxStatus.valueOf(rs.getString("status")),
        rs.getInt("attempts"),
        toInstant(rs, "occurred_at"),
        toInstant(rs, "available_at"),
        toInstant(rs, "published_at"));
  }

  private static java.time.Instant toInstant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}

package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.OutboxRepository.OutboxMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class OutboxRowMapper implements RowMapper<OutboxMessage> {

  static final OutboxRowMapper INSTANCE = new OutboxRowMapper();

  private OutboxRowMapper() {}

  @Override
  public OutboxMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new OutboxMessage(
        rs.getObject("message_id", java.util.UUID.class),
        rs.getString("message_type"),
        rs.getInt("message_version"),
        rs.getString("message_kind"),
        rs.getString("producer"),
        rs.getString("routing_key"),
        rs.getObject("operation_id", java.util.UUID.class),
        rs.getString("idempotency_key"),
        ActorJsonCodec.read(rs.getString("actor")),
        rs.getObject("aggregate_id", java.util.UUID.class),
        (Integer) rs.getObject("aggregate_version"),
        rs.getObject("correlation_id", java.util.UUID.class),
        rs.getObject("causation_id", java.util.UUID.class),
        rs.getString("payload"),
        rs.getString("status"),
        rs.getInt("attempts"),
        Timestamps.toInstant(rs, "occurred_at"),
        Timestamps.toInstant(rs, "available_at"),
        Timestamps.toInstant(rs, "published_at"),
        rs.getString("last_error_code"),
        rs.getString("last_error_detail"),
        rs.getObject("claim_token", java.util.UUID.class),
        Timestamps.toInstant(rs, "claimed_at"),
        Timestamps.toInstant(rs, "lease_until"));
  }
}

package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.ProcessedMessageRepository.ProcessedMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class ProcessedMessageRowMapper implements RowMapper<ProcessedMessage> {

  static final ProcessedMessageRowMapper INSTANCE = new ProcessedMessageRowMapper();

  private ProcessedMessageRowMapper() {}

  @Override
  public ProcessedMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new ProcessedMessage(
        rs.getString("consumer_name"),
        rs.getObject("message_id", java.util.UUID.class),
        rs.getString("status"),
        rs.getString("message_type"),
        rs.getInt("message_version"),
        rs.getString("message_kind"),
        rs.getString("producer"),
        rs.getObject("correlation_id", java.util.UUID.class),
        rs.getObject("causation_id", java.util.UUID.class),
        rs.getObject("operation_id", java.util.UUID.class),
        rs.getString("idempotency_key"),
        rs.getObject("aggregate_id", java.util.UUID.class),
        (Integer) rs.getObject("aggregate_version"),
        ActorJsonCodec.read(rs.getString("actor")),
        rs.getString("payload"),
        Timestamps.toInstant(rs, "received_at"),
        Timestamps.toInstant(rs, "occurred_at"),
        Timestamps.toInstant(rs, "applied_at"),
        rs.getString("deferred_reason"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}

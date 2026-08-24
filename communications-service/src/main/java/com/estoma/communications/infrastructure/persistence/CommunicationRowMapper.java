package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationStatus;
import com.estoma.communications.domain.communication.Communication;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** Maps one row of communications back into the aggregate. */
final class CommunicationRowMapper implements RowMapper<Communication> {

  static final CommunicationRowMapper INSTANCE = new CommunicationRowMapper();

  private CommunicationRowMapper() {}

  @Override
  public Communication mapRow(ResultSet rs, int rowNum) throws SQLException {
    return Communication.reconstitute(
        rs.getObject("communication_id", UUID.class),
        rs.getObject("recipient_account_id", UUID.class),
        rs.getString("system_code"),
        rs.getString("communication_type"),
        CommunicationPriority.valueOf(rs.getString("priority")),
        rs.getString("source_service"),
        rs.getString("source_reference_type"),
        rs.getString("source_reference_id"),
        rs.getObject("initiated_by_account_id", UUID.class),
        CommunicationStatus.valueOf(rs.getString("status")),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "completed_at"),
        Timestamps.toInstant(rs, "failed_at"),
        rs.getArray("requested_channels") == null
            ? List.of()
            : Arrays.asList((String[]) rs.getArray("requested_channels").getArray()),
        rs.getObject("source_message_id", UUID.class),
        rs.getString("idempotency_key"),
        rs.getString("request_fingerprint"),
        rs.getObject("correlation_id", UUID.class),
        rs.getObject("causation_id", UUID.class));
  }
}

package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.contracts.EmailDeliveryStatus;
import com.estoma.communications.domain.email.EmailDelivery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** Maps one row of {@code email_deliveries} back into an {@link EmailDelivery}. */
final class EmailDeliveryRowMapper implements RowMapper<EmailDelivery> {

  static final EmailDeliveryRowMapper INSTANCE = new EmailDeliveryRowMapper();

  private EmailDeliveryRowMapper() {}

  @Override
  public EmailDelivery mapRow(ResultSet rs, int rowNum) throws SQLException {
    return EmailDelivery.reconstitute(
        rs.getObject("email_delivery_id", UUID.class),
        rs.getObject("communication_id", UUID.class),
        rs.getString("recipient_address"),
        rs.getString("recipient_name"),
        rs.getString("from_name"),
        rs.getString("from_address"),
        rs.getString("reply_to_address"),
        EmailContentMode.valueOf(rs.getString("content_mode")),
        rs.getString("subject"),
        rs.getString("text_body"),
        rs.getString("html_body"),
        rs.getString("template_code"),
        rs.getString("template_data"),
        EmailDeliveryStatus.valueOf(rs.getString("status")),
        rs.getInt("attempt_count"),
        Timestamps.toInstant(rs, "next_attempt_at"),
        Timestamps.toInstant(rs, "accepted_at"),
        Timestamps.toInstant(rs, "delivered_at"),
        Timestamps.toInstant(rs, "failed_at"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"),
        Timestamps.toInstant(rs, "created_at"),
         Timestamps.toInstant(rs, "updated_at"), rs.getObject("claim_token", UUID.class),
         Timestamps.toInstant(rs, "claimed_at"), Timestamps.toInstant(rs, "lease_until"), rs.getString("worker_id"),
         rs.getInt("retry_cycle"), rs.getInt("cycle_attempt_count"));
  }
}

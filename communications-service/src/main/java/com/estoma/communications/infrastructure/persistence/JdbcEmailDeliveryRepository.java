package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.domain.email.EmailDelivery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcEmailDeliveryRepository implements EmailDeliveryRepository {

  private static final String COLUMNS =
      """
      email_delivery_id, communication_id, recipient_address, recipient_name, from_name, from_address, reply_to_address,
      content_mode, subject, text_body, html_body, template_code, template_data,
      status, attempt_count, next_attempt_at, accepted_at, delivered_at, failed_at, last_error_code, last_error_message,
      created_at, updated_at
      """;

  private final JdbcClient jdbc;

  JdbcEmailDeliveryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<EmailDelivery> findById(UUID emailDeliveryId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM email_deliveries WHERE email_delivery_id = :emailDeliveryId")
        .param("emailDeliveryId", emailDeliveryId)
        .query(EmailDeliveryRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public void insert(EmailDelivery delivery) {
    jdbc.sql(
            """
            INSERT INTO email_deliveries (
              email_delivery_id, communication_id, recipient_address, recipient_name, from_name, from_address, reply_to_address,
              content_mode, subject, text_body, html_body, template_code, template_data,
              status, attempt_count, next_attempt_at, accepted_at, delivered_at, failed_at, last_error_code, last_error_message,
              created_at, updated_at
            ) VALUES (
              :emailDeliveryId, :communicationId, :recipientAddress, :recipientName, :fromName, :fromAddress, :replyToAddress,
              CAST(:contentMode AS email_content_mode), :subject, :textBody, :htmlBody, :templateCode, CAST(:templateData AS jsonb),
              CAST(:status AS email_delivery_status), :attemptCount, :nextAttemptAt, :acceptedAt, :deliveredAt, :failedAt, :lastErrorCode, :lastErrorMessage,
              :createdAt, :updatedAt
            )
            """)
        .param("emailDeliveryId", delivery.emailDeliveryId())
        .param("communicationId", delivery.communicationId())
        .param("recipientAddress", delivery.recipientAddress())
        .param("recipientName", delivery.recipientName())
        .param("fromName", delivery.fromName())
        .param("fromAddress", delivery.fromAddress())
        .param("replyToAddress", delivery.replyToAddress())
        .param("contentMode", delivery.contentMode().name())
        .param("subject", delivery.subject())
        .param("textBody", delivery.textBody())
        .param("htmlBody", delivery.htmlBody())
        .param("templateCode", delivery.templateCode())
        .param("templateData", delivery.templateData())
        .param("status", delivery.status().name())
        .param("attemptCount", delivery.attemptCount())
        .param("nextAttemptAt", Timestamps.toTimestamp(delivery.nextAttemptAt()))
        .param("acceptedAt", Timestamps.toTimestamp(delivery.acceptedAt()))
        .param("deliveredAt", Timestamps.toTimestamp(delivery.deliveredAt()))
        .param("failedAt", Timestamps.toTimestamp(delivery.failedAt()))
        .param("lastErrorCode", delivery.lastErrorCode())
        .param("lastErrorMessage", delivery.lastErrorMessage())
        .param("createdAt", Timestamps.toTimestamp(delivery.createdAt()))
        .param("updatedAt", Timestamps.toTimestamp(delivery.updatedAt()))
        .update();
  }

  @Override
  public int update(EmailDelivery delivery) {
    return jdbc.sql(
            """
            UPDATE email_deliveries SET
              status = CAST(:status AS email_delivery_status),
              attempt_count = :attemptCount,
              next_attempt_at = :nextAttemptAt,
              accepted_at = :acceptedAt,
              delivered_at = :deliveredAt,
              failed_at = :failedAt,
              last_error_code = :lastErrorCode,
              last_error_message = :lastErrorMessage,
              updated_at = :updatedAt
            WHERE email_delivery_id = :emailDeliveryId
            """)
        .param("status", delivery.status().name())
        .param("attemptCount", delivery.attemptCount())
        .param("nextAttemptAt", Timestamps.toTimestamp(delivery.nextAttemptAt()))
        .param("acceptedAt", Timestamps.toTimestamp(delivery.acceptedAt()))
        .param("deliveredAt", Timestamps.toTimestamp(delivery.deliveredAt()))
        .param("failedAt", Timestamps.toTimestamp(delivery.failedAt()))
        .param("lastErrorCode", delivery.lastErrorCode())
        .param("lastErrorMessage", delivery.lastErrorMessage())
        .param("updatedAt", Timestamps.toTimestamp(delivery.updatedAt()))
        .param("emailDeliveryId", delivery.emailDeliveryId())
        .update();
  }

  @Override
  public List<EmailDelivery> claimDue(int batchSize) {
    return jdbc.sql(
            """
            WITH due AS (
              SELECT email_delivery_id FROM email_deliveries
              WHERE status IN (CAST('PENDING' AS email_delivery_status), CAST('RETRY_SCHEDULED' AS email_delivery_status))
                AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
              ORDER BY created_at
              LIMIT :batchSize
              FOR UPDATE SKIP LOCKED
            )
            UPDATE email_deliveries e
            SET status = CAST('SENDING' AS email_delivery_status), updated_at = :now
            FROM due
            WHERE e.email_delivery_id = due.email_delivery_id
            RETURNING
              e.email_delivery_id, e.communication_id, e.recipient_address, e.recipient_name, e.from_name, e.from_address, e.reply_to_address,
              e.content_mode, e.subject, e.text_body, e.html_body, e.template_code, e.template_data,
              e.status, e.attempt_count, e.next_attempt_at, e.accepted_at, e.delivered_at, e.failed_at, e.last_error_code, e.last_error_message,
              e.created_at, e.updated_at
            """)
        .param("now", Timestamps.toTimestamp(Instant.now()))
        .param("batchSize", batchSize)
        .query(EmailDeliveryRowMapper.INSTANCE)
        .list();
  }
}

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
       email_delivery_id, communication_id, recipient_address, recipient_name, from_name, from_address, reply_to_address, sender_code,
      content_mode, subject, text_body, html_body, template_code, template_data,
      status, attempt_count, next_attempt_at, accepted_at, delivered_at, failed_at, last_error_code, last_error_message,
       created_at, updated_at, claim_token, claimed_at, lease_until, worker_id, retry_cycle, cycle_attempt_count
      """;

  private final JdbcClient jdbc;
  private final long leaseDurationMs;

  JdbcEmailDeliveryRepository(JdbcClient jdbc,
      @org.springframework.beans.factory.annotation.Value("${estoma.communications.email.worker.lease-duration-ms:30000}") long leaseDurationMs) {
    this.jdbc = jdbc;
    this.leaseDurationMs = leaseDurationMs;
  }

  @Override
  public Optional<EmailDelivery> findById(UUID emailDeliveryId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM email_deliveries WHERE email_delivery_id = :emailDeliveryId")
        .param("emailDeliveryId", emailDeliveryId)
        .query(EmailDeliveryRowMapper.INSTANCE)
        .optional();
  }
  @Override public Optional<EmailDelivery> findByIdForUpdate(UUID emailDeliveryId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM email_deliveries WHERE email_delivery_id = :emailDeliveryId FOR UPDATE")
        .param("emailDeliveryId", emailDeliveryId).query(EmailDeliveryRowMapper.INSTANCE).optional();
  }

  @Override
  public void insert(EmailDelivery delivery) {
    jdbc.sql(
            """
            INSERT INTO email_deliveries (
               email_delivery_id, communication_id, recipient_address, recipient_name, from_name, from_address, reply_to_address, sender_code,
              content_mode, subject, text_body, html_body, template_code, template_data,
              status, attempt_count, next_attempt_at, accepted_at, delivered_at, failed_at, last_error_code, last_error_message,
                created_at, updated_at, claim_token, claimed_at, lease_until, worker_id, retry_cycle, cycle_attempt_count
            ) VALUES (
               :emailDeliveryId, :communicationId, :recipientAddress, :recipientName, :fromName, :fromAddress, :replyToAddress, :senderCode,
              CAST(:contentMode AS email_content_mode), :subject, :textBody, :htmlBody, :templateCode, CAST(:templateData AS jsonb),
              CAST(:status AS email_delivery_status), :attemptCount, :nextAttemptAt, :acceptedAt, :deliveredAt, :failedAt, :lastErrorCode, :lastErrorMessage,
                :createdAt, :updatedAt, :claimToken, :claimedAt, :leaseUntil, :workerId, :retryCycle, :cycleAttemptCount
            )
            """)
        .param("emailDeliveryId", delivery.emailDeliveryId())
        .param("communicationId", delivery.communicationId())
        .param("recipientAddress", delivery.recipientAddress())
        .param("recipientName", delivery.recipientName())
        .param("fromName", delivery.fromName())
        .param("fromAddress", delivery.fromAddress())
         .param("replyToAddress", delivery.replyToAddress())
         .param("senderCode", delivery.senderCode())
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
         .param("claimToken", delivery.claimToken()).param("claimedAt", Timestamps.toTimestamp(delivery.claimedAt()))
         .param("leaseUntil", Timestamps.toTimestamp(delivery.leaseUntil())).param("workerId", delivery.workerId())
         .param("retryCycle", delivery.retryCycle()).param("cycleAttemptCount", delivery.cycleAttemptCount())
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
              updated_at = :updatedAt, claim_token = :claimToken, claimed_at = :claimedAt,
              lease_until = :leaseUntil, worker_id = :workerId, retry_cycle = :retryCycle, cycle_attempt_count = :cycleAttemptCount
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
        .param("claimToken", delivery.claimToken()).param("claimedAt", Timestamps.toTimestamp(delivery.claimedAt()))
        .param("leaseUntil", Timestamps.toTimestamp(delivery.leaseUntil())).param("workerId", delivery.workerId())
        .param("retryCycle", delivery.retryCycle()).param("cycleAttemptCount", delivery.cycleAttemptCount())
        .param("emailDeliveryId", delivery.emailDeliveryId())
        .update();
  }

  @Override
  public int persistRenderedContent(EmailDelivery delivery, UUID claimToken) {
    return jdbc.sql("""
        UPDATE email_deliveries SET subject = :subject, text_body = :textBody, html_body = :htmlBody, updated_at = :updatedAt
         WHERE email_delivery_id = :id AND claim_token = :token AND lease_until > :now
           AND status = CAST('SENDING' AS email_delivery_status)
           AND subject IS NULL AND text_body IS NULL AND html_body IS NULL
        """)
        .param("subject", delivery.subject()).param("textBody", delivery.textBody()).param("htmlBody", delivery.htmlBody())
        .param("updatedAt", Timestamps.toTimestamp(delivery.updatedAt())).param("id", delivery.emailDeliveryId()).param("token", claimToken)
        .param("now", Timestamps.toTimestamp(Instant.now())).update();
  }

  @Override
  public List<EmailDelivery> claimDue(int batchSize) {
    return claimDue(batchSize, "legacy-worker");
  }

  @Override
  public List<EmailDelivery> claimDue(int batchSize, String workerId) {
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
            SET status = CAST('SENDING' AS email_delivery_status), claim_token = gen_random_uuid(), claimed_at = :now, lease_until = :leaseUntil,
                worker_id = :workerId, updated_at = :now
            FROM due
            WHERE e.email_delivery_id = due.email_delivery_id
            RETURNING
               e.email_delivery_id, e.communication_id, e.recipient_address, e.recipient_name, e.from_name, e.from_address, e.reply_to_address, e.sender_code,
              e.content_mode, e.subject, e.text_body, e.html_body, e.template_code, e.template_data,
              e.status, e.attempt_count, e.next_attempt_at, e.accepted_at, e.delivered_at, e.failed_at, e.last_error_code, e.last_error_message,
              e.created_at, e.updated_at, e.claim_token, e.claimed_at, e.lease_until, e.worker_id, e.retry_cycle, e.cycle_attempt_count
            """)
        .param("now", Timestamps.toTimestamp(Instant.now()))
         .param("batchSize", batchSize)
           .param("leaseUntil", Timestamps.toTimestamp(Instant.now().plusMillis(leaseDurationMs))).param("workerId", workerId)
        .query(EmailDeliveryRowMapper.INSTANCE)
        .list();
  }

  @Override
  public int releaseExpiredLeases() {
    // Lock order is delivery then its attempt. READY attempts are retained for
    // reuse; CALL_STARTED means a provider call may have escaped the process.
    return jdbc.sql("""
        WITH expired AS (
          SELECT d.email_delivery_id, d.claim_token FROM email_deliveries d
           WHERE d.status = CAST('SENDING' AS email_delivery_status)
             AND d.lease_until IS NOT NULL AND d.lease_until < now()
           FOR UPDATE SKIP LOCKED
        ), locked_attempts AS (
          SELECT a.email_delivery_attempt_id, a.email_delivery_id, a.status
            FROM email_delivery_attempts a JOIN expired e ON e.email_delivery_id = a.email_delivery_id
           WHERE a.claim_token = e.claim_token FOR UPDATE
        ), started AS (
          UPDATE email_delivery_attempts a SET status = CAST('INDETERMINATE' AS email_attempt_status),
            normalized_error_code = 'LEASE_EXPIRED', error_message = 'provider call may have started',
            completed_at = now(), claim_token = NULL
           FROM locked_attempts l WHERE a.email_delivery_attempt_id = l.email_delivery_attempt_id
             AND l.status = CAST('CALL_STARTED' AS email_attempt_status)
          RETURNING a.email_delivery_id
        ), ready AS (
          UPDATE email_delivery_attempts a SET claim_token = NULL
           FROM locked_attempts l WHERE a.email_delivery_attempt_id = l.email_delivery_attempt_id
             AND l.status = CAST('READY_TO_SEND' AS email_attempt_status)
        )
        UPDATE email_deliveries d SET
          status = CASE WHEN EXISTS (SELECT 1 FROM started s WHERE s.email_delivery_id = d.email_delivery_id)
                    THEN CAST('INDETERMINATE' AS email_delivery_status) ELSE CAST('PENDING' AS email_delivery_status) END,
          next_attempt_at = CASE WHEN EXISTS (SELECT 1 FROM started s WHERE s.email_delivery_id = d.email_delivery_id)
                    THEN NULL ELSE now() END,
          claim_token = NULL, claimed_at = NULL, lease_until = NULL, worker_id = NULL, updated_at = now()
         FROM expired e WHERE d.email_delivery_id = e.email_delivery_id
        """).update();
  }

  @Override
  public int startSending(UUID deliveryId, UUID claimToken, Instant now) {
    return jdbc.sql("""
        UPDATE email_deliveries SET status = CAST('SENDING' AS email_delivery_status),
          attempt_count = attempt_count + 1, cycle_attempt_count = cycle_attempt_count + 1, updated_at = :now
        WHERE email_delivery_id = :id AND claim_token = :token AND lease_until > :now
          AND status = CAST('SENDING' AS email_delivery_status)
        """)
        .param("id", deliveryId).param("token", claimToken).param("now", Timestamps.toTimestamp(now)).update();
  }

  @Override
  public int failPreProvider(UUID deliveryId, UUID claimToken, String code, String message, Instant now) {
    return jdbc.sql("""
        UPDATE email_deliveries SET status = CAST('FAILED' AS email_delivery_status),
          failed_at = :now, last_error_code = :code, last_error_message = :message,
          claim_token = NULL, claimed_at = NULL, lease_until = NULL, worker_id = NULL, updated_at = :now
         WHERE email_delivery_id = :id AND claim_token = :token AND lease_until > :now AND status IN
          (CAST('PENDING' AS email_delivery_status), CAST('RETRY_SCHEDULED' AS email_delivery_status), CAST('SENDING' AS email_delivery_status))
        """).param("now", Timestamps.toTimestamp(now)).param("code", code).param("message", message)
        .param("id", deliveryId).param("token", claimToken).param("now", Timestamps.toTimestamp(now)).update();
  }

  @Override
  public int requeueUnstarted(UUID deliveryId, UUID claimToken, Instant now) {
    return jdbc.sql("""
        UPDATE email_deliveries SET status = CAST('PENDING' AS email_delivery_status), next_attempt_at = :now,
          attempt_count = attempt_count - 1, cycle_attempt_count = cycle_attempt_count - 1,
          claim_token = NULL, claimed_at = NULL, lease_until = NULL, worker_id = NULL, updated_at = :now
         WHERE email_delivery_id = :id AND claim_token = :token AND lease_until > :now
           AND status = CAST('SENDING' AS email_delivery_status)
        """).param("id", deliveryId).param("token", claimToken).param("now", Timestamps.toTimestamp(now)).update();
  }

  @Override
  public int updateClaimed(EmailDelivery delivery, UUID claimToken) {
    return jdbc.sql("""
        UPDATE email_deliveries SET status = CAST(:status AS email_delivery_status), next_attempt_at = :nextAttemptAt,
          accepted_at = :acceptedAt, delivered_at = :deliveredAt, failed_at = :failedAt, last_error_code = :code,
          last_error_message = :message, updated_at = :updatedAt, claim_token = NULL, claimed_at = NULL,
          lease_until = NULL, worker_id = NULL
         WHERE email_delivery_id = :id AND claim_token = :token AND lease_until > now()
           AND status = CAST('SENDING' AS email_delivery_status)
        """).param("status", delivery.status().name()).param("nextAttemptAt", Timestamps.toTimestamp(delivery.nextAttemptAt()))
        .param("acceptedAt", Timestamps.toTimestamp(delivery.acceptedAt())).param("deliveredAt", Timestamps.toTimestamp(delivery.deliveredAt()))
        .param("failedAt", Timestamps.toTimestamp(delivery.failedAt())).param("code", delivery.lastErrorCode()).param("message", delivery.lastErrorMessage())
        .param("updatedAt", Timestamps.toTimestamp(delivery.updatedAt())).param("id", delivery.emailDeliveryId()).param("token", claimToken).update();
  }
}

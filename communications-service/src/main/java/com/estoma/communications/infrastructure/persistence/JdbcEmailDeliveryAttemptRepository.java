package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.EmailDeliveryAttemptRepository;
import com.estoma.communications.domain.email.EmailDeliveryAttempt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.UUID;
import java.util.Optional;

@Repository
class JdbcEmailDeliveryAttemptRepository implements EmailDeliveryAttemptRepository {

  private final JdbcClient jdbc;

  JdbcEmailDeliveryAttemptRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(EmailDeliveryAttempt attempt) {
    jdbc.sql(
            """
            INSERT INTO email_delivery_attempts (
              email_delivery_attempt_id, email_delivery_id, attempt_number, retry_cycle, cycle_attempt_number, provider, delivery_mode, status,
              provider_message_id, normalized_error_code, error_message, started_at, call_started_at, completed_at, claim_token
             ) VALUES (
              :emailDeliveryAttemptId, :emailDeliveryId, :attemptNumber, :retryCycle, :cycleAttemptNumber, :provider, CAST(:deliveryMode AS email_content_mode),
              CAST(:status AS email_attempt_status), :providerMessageId, :normalizedErrorCode, :errorMessage, :startedAt, :callStartedAt, :completedAt, :claimToken
             )
             ON CONFLICT (email_delivery_id, attempt_number) DO NOTHING
            """)
        .param("emailDeliveryAttemptId", attempt.emailDeliveryAttemptId())
        .param("emailDeliveryId", attempt.emailDeliveryId())
        .param("attemptNumber", attempt.attemptNumber())
        .param("retryCycle", attempt.retryCycle())
        .param("cycleAttemptNumber", attempt.cycleAttemptNumber())
        .param("provider", attempt.provider())
        .param("deliveryMode", attempt.deliveryMode().name())
        .param("status", attempt.status().name())
        .param("providerMessageId", attempt.providerMessageId())
        .param("normalizedErrorCode", attempt.normalizedErrorCode())
        .param("errorMessage", attempt.errorMessage())
        .param("startedAt", Timestamps.toTimestamp(attempt.startedAt()))
        .param("callStartedAt", Timestamps.toTimestamp(attempt.callStartedAt()))
        .param("completedAt", Timestamps.toTimestamp(attempt.completedAt()))
        .param("claimToken", attempt.claimToken())
        .update();
  }

  @Override public int update(EmailDeliveryAttempt attempt) {
    return jdbc.sql("UPDATE email_delivery_attempts SET status = CAST(:status AS email_attempt_status), provider_message_id = :providerMessageId, normalized_error_code = :errorCode, error_message = :message, call_started_at = :callStartedAt, completed_at = :completedAt WHERE email_delivery_attempt_id = :id AND claim_token = :claimToken")
        .param("status", attempt.status().name()).param("providerMessageId", attempt.providerMessageId()).param("errorCode", attempt.normalizedErrorCode())
        .param("message", attempt.errorMessage()).param("callStartedAt", Timestamps.toTimestamp(attempt.callStartedAt()))
        .param("completedAt", Timestamps.toTimestamp(attempt.completedAt())).param("id", attempt.emailDeliveryAttemptId()).param("claimToken", attempt.claimToken()).update();
  }

  @Override public Optional<EmailDeliveryAttempt> findByClaim(UUID deliveryId, UUID claimToken) {
    return jdbc.sql("SELECT * FROM email_delivery_attempts WHERE email_delivery_id = :deliveryId AND claim_token = :claimToken ORDER BY attempt_number DESC LIMIT 1")
        .param("deliveryId", deliveryId).param("claimToken", claimToken).query((rs, row) -> EmailDeliveryAttempt.reconstitute(
            rs.getObject("email_delivery_attempt_id", UUID.class), rs.getObject("email_delivery_id", UUID.class), rs.getInt("attempt_number"),
            rs.getInt("retry_cycle"), rs.getInt("cycle_attempt_number"), rs.getObject("claim_token", UUID.class), rs.getString("provider"),
            com.estoma.communications.contracts.EmailContentMode.valueOf(rs.getString("delivery_mode")),
            com.estoma.communications.contracts.EmailAttemptStatus.valueOf(rs.getString("status")), rs.getString("provider_message_id"),
            rs.getString("normalized_error_code"), rs.getString("error_message"), Timestamps.toInstant(rs, "started_at"),
            Timestamps.toInstant(rs, "call_started_at"), Timestamps.toInstant(rs, "completed_at"))).optional();
  }

  @Override public Optional<EmailDeliveryAttempt> findReadyByDelivery(UUID deliveryId) {
    return jdbc.sql("SELECT * FROM email_delivery_attempts WHERE email_delivery_id = :deliveryId AND status = CAST('READY_TO_SEND' AS email_attempt_status) ORDER BY attempt_number DESC LIMIT 1")
        .param("deliveryId", deliveryId).query((rs, row) -> EmailDeliveryAttempt.reconstitute(
            rs.getObject("email_delivery_attempt_id", UUID.class), rs.getObject("email_delivery_id", UUID.class),
            rs.getInt("attempt_number"), rs.getInt("retry_cycle"), rs.getInt("cycle_attempt_number"),
            rs.getObject("claim_token", UUID.class), rs.getString("provider"),
            com.estoma.communications.contracts.EmailContentMode.valueOf(rs.getString("delivery_mode")),
            com.estoma.communications.contracts.EmailAttemptStatus.valueOf(rs.getString("status")),
            rs.getString("provider_message_id"), rs.getString("normalized_error_code"), rs.getString("error_message"),
            Timestamps.toInstant(rs, "started_at"), Timestamps.toInstant(rs, "call_started_at"),
            Timestamps.toInstant(rs, "completed_at"))).optional();
  }

  @Override public Optional<EmailDeliveryAttempt> findLastIndeterminate(UUID deliveryId) {
    return jdbc.sql("SELECT * FROM email_delivery_attempts WHERE email_delivery_id = :deliveryId AND status = CAST('INDETERMINATE' AS email_attempt_status) ORDER BY attempt_number DESC LIMIT 1")
        .param("deliveryId", deliveryId).query((rs, row) -> EmailDeliveryAttempt.reconstitute(
            rs.getObject("email_delivery_attempt_id", UUID.class), rs.getObject("email_delivery_id", UUID.class), rs.getInt("attempt_number"),
            rs.getInt("retry_cycle"), rs.getInt("cycle_attempt_number"), rs.getObject("claim_token", UUID.class), rs.getString("provider"),
            com.estoma.communications.contracts.EmailContentMode.valueOf(rs.getString("delivery_mode")),
            com.estoma.communications.contracts.EmailAttemptStatus.valueOf(rs.getString("status")), rs.getString("provider_message_id"),
            rs.getString("normalized_error_code"), rs.getString("error_message"), Timestamps.toInstant(rs, "started_at"),
            Timestamps.toInstant(rs, "call_started_at"), Timestamps.toInstant(rs, "completed_at"))).optional();
  }

  @Override public int reassignClaim(UUID attemptId, UUID previousToken, UUID newToken) {
    return jdbc.sql("UPDATE email_delivery_attempts SET claim_token = :newToken WHERE email_delivery_attempt_id = :id AND claim_token = :oldToken AND status = CAST('READY_TO_SEND' AS email_attempt_status)")
        .param("id", attemptId).param("oldToken", previousToken).param("newToken", newToken).update();
  }
}

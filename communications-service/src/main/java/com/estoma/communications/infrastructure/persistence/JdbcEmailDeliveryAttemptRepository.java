package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.EmailDeliveryAttemptRepository;
import com.estoma.communications.domain.email.EmailDeliveryAttempt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
              email_delivery_attempt_id, email_delivery_id, attempt_number, provider, delivery_mode, status,
              provider_message_id, normalized_error_code, error_message, started_at, completed_at
            ) VALUES (
              :emailDeliveryAttemptId, :emailDeliveryId, :attemptNumber, :provider, CAST(:deliveryMode AS email_content_mode),
              CAST(:status AS email_attempt_status), :providerMessageId, :normalizedErrorCode, :errorMessage, :startedAt, :completedAt
            )
            """)
        .param("emailDeliveryAttemptId", attempt.emailDeliveryAttemptId())
        .param("emailDeliveryId", attempt.emailDeliveryId())
        .param("attemptNumber", attempt.attemptNumber())
        .param("provider", attempt.provider())
        .param("deliveryMode", attempt.deliveryMode().name())
        .param("status", attempt.status().name())
        .param("providerMessageId", attempt.providerMessageId())
        .param("normalizedErrorCode", attempt.normalizedErrorCode())
        .param("errorMessage", attempt.errorMessage())
        .param("startedAt", Timestamps.toTimestamp(attempt.startedAt()))
        .param("completedAt", Timestamps.toTimestamp(attempt.completedAt()))
        .update();
  }
}

package com.estoma.communications.domain.email;

import com.estoma.communications.contracts.EmailAttemptStatus;
import com.estoma.communications.contracts.EmailContentMode;
import java.time.Instant;
import java.util.UUID;

/** One provider interaction for a given email delivery (data-model.md §6). */
public final class EmailDeliveryAttempt {

  private final UUID emailDeliveryAttemptId;
  private final UUID emailDeliveryId;
  private final int attemptNumber;
  private final String provider;
  private final EmailContentMode deliveryMode;
  private final EmailAttemptStatus status;
  private final String providerMessageId;
  private final String normalizedErrorCode;
  private final String errorMessage;
  private final Instant startedAt;
  private final Instant completedAt;

  private EmailDeliveryAttempt(
      UUID emailDeliveryAttemptId,
      UUID emailDeliveryId,
      int attemptNumber,
      String provider,
      EmailContentMode deliveryMode,
      EmailAttemptStatus status,
      String providerMessageId,
      String normalizedErrorCode,
      String errorMessage,
      Instant startedAt,
      Instant completedAt) {
    this.emailDeliveryAttemptId = requireNonNull(emailDeliveryAttemptId, "emailDeliveryAttemptId");
    this.emailDeliveryId = requireNonNull(emailDeliveryId, "emailDeliveryId");
    if (attemptNumber <= 0) {
      throw new IllegalArgumentException("attemptNumber must be positive");
    }
    this.attemptNumber = attemptNumber;
    this.provider = requireNonNull(provider, "provider");
    this.deliveryMode = requireNonNull(deliveryMode, "deliveryMode");
    this.status = requireNonNull(status, "status");
    this.providerMessageId = providerMessageId;
    this.normalizedErrorCode = normalizedErrorCode;
    this.errorMessage = errorMessage;
    this.startedAt = requireNonNull(startedAt, "startedAt");
    this.completedAt = requireNonNull(completedAt, "completedAt");
  }

  public static EmailDeliveryAttempt accepted(
      UUID emailDeliveryAttemptId, UUID emailDeliveryId, int attemptNumber, String provider, EmailContentMode deliveryMode, String providerMessageId,
      Instant startedAt, Instant completedAt) {
    return new EmailDeliveryAttempt(
        emailDeliveryAttemptId, emailDeliveryId, attemptNumber, provider, deliveryMode, EmailAttemptStatus.ACCEPTED, providerMessageId, null, null,
        startedAt, completedAt);
  }

  public static EmailDeliveryAttempt failed(
      UUID emailDeliveryAttemptId, UUID emailDeliveryId, int attemptNumber, String provider, EmailContentMode deliveryMode, String normalizedErrorCode,
      String errorMessage, Instant startedAt, Instant completedAt) {
    return new EmailDeliveryAttempt(
        emailDeliveryAttemptId, emailDeliveryId, attemptNumber, provider, deliveryMode, EmailAttemptStatus.FAILED, null, normalizedErrorCode, errorMessage,
        startedAt, completedAt);
  }

  public UUID emailDeliveryAttemptId() {
    return emailDeliveryAttemptId;
  }

  public UUID emailDeliveryId() {
    return emailDeliveryId;
  }

  public int attemptNumber() {
    return attemptNumber;
  }

  public String provider() {
    return provider;
  }

  public EmailContentMode deliveryMode() {
    return deliveryMode;
  }

  public EmailAttemptStatus status() {
    return status;
  }

  public String providerMessageId() {
    return providerMessageId;
  }

  public String normalizedErrorCode() {
    return normalizedErrorCode;
  }

  public String errorMessage() {
    return errorMessage;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant completedAt() {
    return completedAt;
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " must not be null");
    }
    return value;
  }
}

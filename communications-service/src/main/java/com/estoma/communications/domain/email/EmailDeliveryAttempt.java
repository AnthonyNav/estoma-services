package com.estoma.communications.domain.email;

import com.estoma.communications.contracts.EmailAttemptStatus;
import com.estoma.communications.contracts.EmailContentMode;
import java.time.Instant;
import java.util.UUID;

/** A durable provider interaction, including the pre-call lifecycle. */
public final class EmailDeliveryAttempt {
  private final UUID id;
  private final UUID deliveryId;
  private final int attemptNumber;
  private final int retryCycle;
  private final int cycleAttemptNumber;
  private final UUID claimToken;
  private final String provider;
  private final EmailContentMode deliveryMode;
  private final EmailAttemptStatus status;
  private final String providerMessageId;
  private final String normalizedErrorCode;
  private final String errorMessage;
  private final Instant startedAt;
  private final Instant callStartedAt;
  private final Instant completedAt;

  private EmailDeliveryAttempt(UUID id, UUID deliveryId, int attemptNumber, int retryCycle, int cycleAttemptNumber,
      UUID claimToken, String provider, EmailContentMode mode, EmailAttemptStatus status, String providerMessageId,
      String errorCode, String errorMessage, Instant startedAt, Instant callStartedAt, Instant completedAt) {
    this.id = required(id, "emailDeliveryAttemptId");
    this.deliveryId = required(deliveryId, "emailDeliveryId");
    if (attemptNumber < 1 || retryCycle < 1 || cycleAttemptNumber < 1) throw new IllegalArgumentException("invalid attempt numbers");
    this.attemptNumber = attemptNumber; this.retryCycle = retryCycle; this.cycleAttemptNumber = cycleAttemptNumber;
    this.claimToken = claimToken; this.provider = required(provider, "provider"); this.deliveryMode = required(mode, "deliveryMode");
    this.status = required(status, "status"); this.providerMessageId = providerMessageId; this.normalizedErrorCode = errorCode;
    this.errorMessage = errorMessage; this.startedAt = required(startedAt, "startedAt"); this.callStartedAt = callStartedAt; this.completedAt = completedAt;
  }

  public static EmailDeliveryAttempt readyToSend(UUID id, UUID deliveryId, int attemptNumber, int retryCycle,
      int cycleAttemptNumber, UUID claimToken, String provider, EmailContentMode mode, Instant createdAt) {
    return new EmailDeliveryAttempt(id, deliveryId, attemptNumber, retryCycle, cycleAttemptNumber, claimToken, provider,
        mode, EmailAttemptStatus.READY_TO_SEND, null, null, null, createdAt, null, null);
  }
  public static EmailDeliveryAttempt reconstitute(UUID id, UUID deliveryId, int attemptNumber, int retryCycle,
      int cycleAttemptNumber, UUID claimToken, String provider, EmailContentMode mode, EmailAttemptStatus status,
      String providerMessageId, String errorCode, String errorMessage, Instant startedAt, Instant callStartedAt,
      Instant completedAt) {
    return new EmailDeliveryAttempt(id, deliveryId, attemptNumber, retryCycle, cycleAttemptNumber, claimToken, provider,
        mode, status, providerMessageId, errorCode, errorMessage, startedAt, callStartedAt, completedAt);
  }
  public EmailDeliveryAttempt callStarted(Instant now) {
    return copy(EmailAttemptStatus.CALL_STARTED, null, null, null, now, null);
  }
  public EmailDeliveryAttempt accepted(String providerMessageId, Instant now) {
    return copy(EmailAttemptStatus.ACCEPTED, providerMessageId, null, null, callStartedAt, now);
  }
  public EmailDeliveryAttempt failed(String code, String message, Instant now) {
    return copy(EmailAttemptStatus.FAILED, null, code, message, callStartedAt, now);
  }
  public EmailDeliveryAttempt indeterminate(String code, String message, Instant now) {
    return copy(EmailAttemptStatus.INDETERMINATE, null, code, message, callStartedAt, now);
  }
  private EmailDeliveryAttempt copy(EmailAttemptStatus newStatus, String providerMessageId, String code, String message,
      Instant newCallStartedAt, Instant completedAt) {
    return new EmailDeliveryAttempt(id, deliveryId, attemptNumber, retryCycle, cycleAttemptNumber, claimToken, provider,
        deliveryMode, newStatus, providerMessageId, code, message, startedAt, newCallStartedAt, completedAt);
  }

  public static EmailDeliveryAttempt accepted(UUID id, UUID deliveryId, int number, int retryCycle, int cycleNumber,
      String provider, EmailContentMode mode, String providerMessageId, Instant startedAt, Instant completedAt) {
    return new EmailDeliveryAttempt(id, deliveryId, number, retryCycle, cycleNumber, null, provider, mode,
        EmailAttemptStatus.ACCEPTED, providerMessageId, null, null, startedAt, startedAt, completedAt);
  }
  public static EmailDeliveryAttempt accepted(UUID id, UUID deliveryId, int number, String provider,
      EmailContentMode mode, String providerMessageId, Instant startedAt, Instant completedAt) {
    return accepted(id, deliveryId, number, 1, number, provider, mode, providerMessageId, startedAt, completedAt);
  }
  public static EmailDeliveryAttempt failed(UUID id, UUID deliveryId, int number, String provider, EmailContentMode mode,
      String code, String message, Instant startedAt, Instant completedAt) {
    return new EmailDeliveryAttempt(id, deliveryId, number, 1, number, null, provider, mode,
        EmailAttemptStatus.FAILED, null, code, message, startedAt, startedAt, completedAt);
  }
  public static EmailDeliveryAttempt indeterminate(UUID id, UUID deliveryId, int number, int retryCycle, int cycleNumber,
      String provider, EmailContentMode mode, String code, String message, Instant startedAt, Instant completedAt) {
    return new EmailDeliveryAttempt(id, deliveryId, number, retryCycle, cycleNumber, null, provider, mode,
        EmailAttemptStatus.INDETERMINATE, null, code, message, startedAt, startedAt, completedAt);
  }
  public UUID emailDeliveryAttemptId() { return id; }
  public UUID emailDeliveryId() { return deliveryId; }
  public int attemptNumber() { return attemptNumber; }
  public int retryCycle() { return retryCycle; }
  public int cycleAttemptNumber() { return cycleAttemptNumber; }
  public UUID claimToken() { return claimToken; }
  public String provider() { return provider; }
  public EmailContentMode deliveryMode() { return deliveryMode; }
  public EmailAttemptStatus status() { return status; }
  public String providerMessageId() { return providerMessageId; }
  public String normalizedErrorCode() { return normalizedErrorCode; }
  public String errorMessage() { return errorMessage; }
  public Instant startedAt() { return startedAt; }
  public Instant callStartedAt() { return callStartedAt; }
  public Instant completedAt() { return completedAt; }
  private static <T> T required(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " must not be null"); return value; }
}

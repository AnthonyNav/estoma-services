package com.estoma.communications.domain.email;

import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.contracts.EmailDeliveryStatus;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import java.time.Instant;
import java.util.UUID;

/**
 * The prepared email a communication generates, at most one per
 * communication (system_contract.md "Mandatory Invariants"). {@code
 * GENERIC} content carries {@code subject}/{@code textBody}/{@code
 * htmlBody}; {@code TEMPLATE} content carries {@code templateCode}/{@code
 * templateData} -- {@code templateCode} is a logical identifier only, the
 * infrastructure layer resolves the physical template (commands.md §3).
 *
 * <p>{@code ACCEPTED} means the provider accepted the send request, not
 * that the recipient received it; {@code DELIVERED} would require a real
 * delivery confirmation this service does not support in v1 (no webhooks)
 * and is therefore never reached by this class's transitions yet.
 */
public final class EmailDelivery {

  private final UUID emailDeliveryId;
  private final UUID communicationId;
  private final String recipientAddress;
  private final String recipientName;
  private final String fromName;
  private final String fromAddress;
  private final String replyToAddress;
  private final EmailContentMode contentMode;
  private final String subject;
  private final String textBody;
  private final String htmlBody;
  private final String templateCode;
  private final String templateData;
  private final EmailDeliveryStatus status;
  private final int attemptCount;
  private final Instant nextAttemptAt;
  private final Instant acceptedAt;
  private final Instant deliveredAt;
  private final Instant failedAt;
  private final String lastErrorCode;
  private final String lastErrorMessage;
  private final Instant createdAt;
  private final Instant updatedAt;

  private EmailDelivery(
      UUID emailDeliveryId,
      UUID communicationId,
      String recipientAddress,
      String recipientName,
      String fromName,
      String fromAddress,
      String replyToAddress,
      EmailContentMode contentMode,
      String subject,
      String textBody,
      String htmlBody,
      String templateCode,
      String templateData,
      EmailDeliveryStatus status,
      int attemptCount,
      Instant nextAttemptAt,
      Instant acceptedAt,
      Instant deliveredAt,
      Instant failedAt,
      String lastErrorCode,
      String lastErrorMessage,
      Instant createdAt,
      Instant updatedAt) {
    this.emailDeliveryId = requireNonNull(emailDeliveryId, "emailDeliveryId");
    this.communicationId = requireNonNull(communicationId, "communicationId");
    this.recipientAddress = requireNonBlank(recipientAddress, "recipientAddress");
    this.recipientName = recipientName;
    this.fromName = requireNonBlank(fromName, "fromName");
    this.fromAddress = requireNonBlank(fromAddress, "fromAddress");
    this.replyToAddress = replyToAddress;
    this.contentMode = requireNonNull(contentMode, "contentMode");
    this.subject = subject;
    this.textBody = textBody;
    this.htmlBody = htmlBody;
    this.templateCode = templateCode;
    this.templateData = templateData;
    if (contentMode == EmailContentMode.GENERIC && (subject == null || textBody == null)) {
      throw new IllegalArgumentException("GENERIC content requires subject and textBody");
    }
    if (contentMode == EmailContentMode.TEMPLATE && templateCode == null) {
      throw new IllegalArgumentException("TEMPLATE content requires templateCode");
    }
    this.status = requireNonNull(status, "status");
    if (attemptCount < 0) {
      throw new IllegalArgumentException("attemptCount must not be negative");
    }
    this.attemptCount = attemptCount;
    this.nextAttemptAt = nextAttemptAt;
    this.acceptedAt = acceptedAt;
    this.deliveredAt = deliveredAt;
    this.failedAt = failedAt;
    this.lastErrorCode = lastErrorCode;
    this.lastErrorMessage = lastErrorMessage;
    this.createdAt = requireNonNull(createdAt, "createdAt");
    this.updatedAt = requireNonNull(updatedAt, "updatedAt");
  }

  public static EmailDelivery generic(
      UUID emailDeliveryId,
      UUID communicationId,
      String recipientAddress,
      String recipientName,
      String fromName,
      String fromAddress,
      String replyToAddress,
      String subject,
      String textBody,
      String htmlBody,
      Instant now) {
    return new EmailDelivery(
        emailDeliveryId, communicationId, recipientAddress, recipientName, fromName, fromAddress, replyToAddress,
        EmailContentMode.GENERIC, subject, textBody, htmlBody, null, null, EmailDeliveryStatus.PENDING, 0, null, null,
        null, null, null, null, now, now);
  }

  public static EmailDelivery template(
      UUID emailDeliveryId,
      UUID communicationId,
      String recipientAddress,
      String recipientName,
      String fromName,
      String fromAddress,
      String replyToAddress,
      String templateCode,
      String templateData,
      Instant now) {
    return new EmailDelivery(
        emailDeliveryId, communicationId, recipientAddress, recipientName, fromName, fromAddress, replyToAddress,
        EmailContentMode.TEMPLATE, null, null, null, templateCode, templateData, EmailDeliveryStatus.PENDING, 0, null,
        null, null, null, null, null, now, now);
  }

  public static EmailDelivery reconstitute(
      UUID emailDeliveryId,
      UUID communicationId,
      String recipientAddress,
      String recipientName,
      String fromName,
      String fromAddress,
      String replyToAddress,
      EmailContentMode contentMode,
      String subject,
      String textBody,
      String htmlBody,
      String templateCode,
      String templateData,
      EmailDeliveryStatus status,
      int attemptCount,
      Instant nextAttemptAt,
      Instant acceptedAt,
      Instant deliveredAt,
      Instant failedAt,
      String lastErrorCode,
      String lastErrorMessage,
      Instant createdAt,
      Instant updatedAt) {
    return new EmailDelivery(
        emailDeliveryId, communicationId, recipientAddress, recipientName, fromName, fromAddress, replyToAddress,
        contentMode, subject, textBody, htmlBody, templateCode, templateData, status, attemptCount, nextAttemptAt,
        acceptedAt, deliveredAt, failedAt, lastErrorCode, lastErrorMessage, createdAt, updatedAt);
  }

  /** The delivery worker has claimed this row and is about to call the provider (system_contract.md "Email Delivery Operational Policy"). */
  public EmailDelivery claimedForSending(Instant now) {
    if (status != EmailDeliveryStatus.PENDING && status != EmailDeliveryStatus.RETRY_SCHEDULED) {
      throw new CommunicationsRejectedException(RejectionCode.EMAIL_DELIVERY_NOT_FAILED, "email delivery " + emailDeliveryId + " is not claimable");
    }
    return withStatus(EmailDeliveryStatus.SENDING, acceptedAt, deliveredAt, failedAt, lastErrorCode, lastErrorMessage, attemptCount, nextAttemptAt, now);
  }

  /** The provider accepted the send request. */
  public EmailDelivery accepted(Instant now) {
    return withStatus(EmailDeliveryStatus.ACCEPTED, now, deliveredAt, failedAt, null, null, attemptCount + 1, null, now);
  }

  /** The provider (or a transient failure) requires another attempt later. */
  public EmailDelivery retryScheduled(String normalizedErrorCode, String errorMessage, Instant nextAttemptAt, Instant now) {
    return withStatus(
        EmailDeliveryStatus.RETRY_SCHEDULED, acceptedAt, deliveredAt, failedAt, normalizedErrorCode, errorMessage, attemptCount + 1, nextAttemptAt, now);
  }

  /** Retries exhausted, or a non-retryable provider error. */
  public EmailDelivery failedTerminal(String normalizedErrorCode, String errorMessage, Instant now) {
    return withStatus(EmailDeliveryStatus.FAILED, acceptedAt, now, now, normalizedErrorCode, errorMessage, attemptCount + 1, null, now);
  }

  /** {@code RetryEmailDelivery}: an operator forces a failed delivery back to PENDING for another attempt. */
  public EmailDelivery retriedByOperator(Instant now) {
    if (status == EmailDeliveryStatus.ACCEPTED || status == EmailDeliveryStatus.DELIVERED) {
      throw new CommunicationsRejectedException(RejectionCode.EMAIL_ALREADY_ACCEPTED, "email delivery " + emailDeliveryId + " was already accepted");
    }
    if (status != EmailDeliveryStatus.FAILED) {
      throw new CommunicationsRejectedException(RejectionCode.EMAIL_DELIVERY_NOT_FAILED, "email delivery " + emailDeliveryId + " is not in FAILED state");
    }
    return withStatus(EmailDeliveryStatus.PENDING, acceptedAt, deliveredAt, null, null, null, attemptCount, now, now);
  }

  private EmailDelivery withStatus(
      EmailDeliveryStatus newStatus,
      Instant newAcceptedAt,
      Instant newDeliveredAt,
      Instant newFailedAt,
      String newErrorCode,
      String newErrorMessage,
      int newAttemptCount,
      Instant newNextAttemptAt,
      Instant now) {
    return new EmailDelivery(
        emailDeliveryId, communicationId, recipientAddress, recipientName, fromName, fromAddress, replyToAddress,
        contentMode, subject, textBody, htmlBody, templateCode, templateData, newStatus, newAttemptCount,
        newNextAttemptAt, newAcceptedAt, newDeliveredAt, newFailedAt, newErrorCode, newErrorMessage, createdAt, now);
  }

  public UUID emailDeliveryId() {
    return emailDeliveryId;
  }

  public UUID communicationId() {
    return communicationId;
  }

  public String recipientAddress() {
    return recipientAddress;
  }

  public String recipientName() {
    return recipientName;
  }

  public String fromName() {
    return fromName;
  }

  public String fromAddress() {
    return fromAddress;
  }

  public String replyToAddress() {
    return replyToAddress;
  }

  public EmailContentMode contentMode() {
    return contentMode;
  }

  public String subject() {
    return subject;
  }

  public String textBody() {
    return textBody;
  }

  public String htmlBody() {
    return htmlBody;
  }

  public String templateCode() {
    return templateCode;
  }

  public String templateData() {
    return templateData;
  }

  public EmailDeliveryStatus status() {
    return status;
  }

  public int attemptCount() {
    return attemptCount;
  }

  public Instant nextAttemptAt() {
    return nextAttemptAt;
  }

  public Instant acceptedAt() {
    return acceptedAt;
  }

  public Instant deliveredAt() {
    return deliveredAt;
  }

  public Instant failedAt() {
    return failedAt;
  }

  public String lastErrorCode() {
    return lastErrorCode;
  }

  public String lastErrorMessage() {
    return lastErrorMessage;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " must not be null");
    }
    return value;
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}

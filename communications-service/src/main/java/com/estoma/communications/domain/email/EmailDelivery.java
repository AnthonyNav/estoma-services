package com.estoma.communications.domain.email;

import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.contracts.EmailDeliveryStatus;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import java.time.Instant;
import java.util.UUID;

/** Durable email state. Claims are transport fencing data, not attempts. */
public final class EmailDelivery {
  private final UUID emailDeliveryId;
  private final UUID communicationId;
  private final String recipientAddress;
  private final String recipientName;
  private final String fromName;
  private final String fromAddress;
  private final String replyToAddress;
  private final String senderCode;
  private final EmailContentMode contentMode;
  private final String subject;
  private final String textBody;
  private final String htmlBody;
  private final String templateCode;
  private final String templateData;
  private final EmailDeliveryStatus status;
  private final int attemptCount;
  private final int retryCycle;
  private final int cycleAttemptCount;
  private final Instant nextAttemptAt;
  private final Instant acceptedAt;
  private final Instant deliveredAt;
  private final Instant failedAt;
  private final String lastErrorCode;
  private final String lastErrorMessage;
  private final Instant createdAt;
  private final Instant updatedAt;
  private final UUID claimToken;
  private final Instant claimedAt;
  private final Instant leaseUntil;
  private final String workerId;

  private EmailDelivery(UUID id, UUID communicationId, String recipientAddress, String recipientName,
       String fromName, String fromAddress, String replyToAddress, String senderCode, EmailContentMode contentMode,
      String subject, String textBody, String htmlBody, String templateCode, String templateData,
      EmailDeliveryStatus status, int attemptCount, int retryCycle, int cycleAttemptCount,
      Instant nextAttemptAt, Instant acceptedAt, Instant deliveredAt, Instant failedAt,
      String lastErrorCode, String lastErrorMessage, Instant createdAt, Instant updatedAt,
      UUID claimToken, Instant claimedAt, Instant leaseUntil, String workerId) {
    this.emailDeliveryId = required(id, "emailDeliveryId");
    this.communicationId = required(communicationId, "communicationId");
    this.recipientAddress = nonBlank(recipientAddress, "recipientAddress");
    this.recipientName = recipientName;
    this.fromName = nonBlank(fromName, "fromName");
    this.fromAddress = nonBlank(fromAddress, "fromAddress");
    this.replyToAddress = replyToAddress;
    this.senderCode = senderCode;
    this.contentMode = required(contentMode, "contentMode");
    this.subject = subject;
    this.textBody = textBody;
    this.htmlBody = htmlBody;
    this.templateCode = templateCode;
    this.templateData = templateData;
    if (contentMode == EmailContentMode.GENERIC && (subject == null || textBody == null))
      throw new IllegalArgumentException("GENERIC content requires subject and textBody");
    if (contentMode == EmailContentMode.TEMPLATE && templateCode == null)
      throw new IllegalArgumentException("TEMPLATE content requires templateCode");
    this.status = required(status, "status");
    if (attemptCount < 0 || retryCycle < 1 || cycleAttemptCount < 0)
      throw new IllegalArgumentException("invalid delivery counters");
    this.attemptCount = attemptCount;
    this.retryCycle = retryCycle;
    this.cycleAttemptCount = cycleAttemptCount;
    this.nextAttemptAt = nextAttemptAt;
    this.acceptedAt = acceptedAt;
    this.deliveredAt = deliveredAt;
    this.failedAt = failedAt;
    this.lastErrorCode = lastErrorCode;
    this.lastErrorMessage = lastErrorMessage;
    this.createdAt = required(createdAt, "createdAt");
    this.updatedAt = required(updatedAt, "updatedAt");
    this.claimToken = claimToken;
    this.claimedAt = claimedAt;
    this.leaseUntil = leaseUntil;
    this.workerId = workerId;
  }

  public static EmailDelivery generic(UUID id, UUID communicationId, String recipientAddress, String recipientName,
      String fromName, String fromAddress, String replyToAddress, String subject, String textBody,
      String htmlBody, Instant now) {
    return generic(id, communicationId, recipientAddress, recipientName, fromName, fromAddress, replyToAddress,
        null, subject, textBody, htmlBody, now);
  }
  public static EmailDelivery generic(UUID id, UUID communicationId, String recipientAddress, String recipientName,
      String fromName, String fromAddress, String replyToAddress, String senderCode, String subject, String textBody,
      String htmlBody, Instant now) {
    return base(id, communicationId, recipientAddress, recipientName, fromName, fromAddress, replyToAddress,
        senderCode, EmailContentMode.GENERIC, subject, textBody, htmlBody, null, null, now);
  }

  public static EmailDelivery template(UUID id, UUID communicationId, String recipientAddress, String recipientName,
      String fromName, String fromAddress, String replyToAddress, String templateCode, String templateData, Instant now) {
    return template(id, communicationId, recipientAddress, recipientName, fromName, fromAddress, replyToAddress,
        null, templateCode, templateData, now);
  }
  public static EmailDelivery template(UUID id, UUID communicationId, String recipientAddress, String recipientName,
      String fromName, String fromAddress, String replyToAddress, String senderCode, String templateCode, String templateData, Instant now) {
    return base(id, communicationId, recipientAddress, recipientName, fromName, fromAddress, replyToAddress,
        senderCode, EmailContentMode.TEMPLATE, null, null, null, templateCode, templateData, now);
  }

  public static EmailDelivery withRenderedContent(EmailDelivery source, String subject, String textBody, String htmlBody) {
    return new EmailDelivery(source.emailDeliveryId, source.communicationId, source.recipientAddress, source.recipientName,
        source.fromName, source.fromAddress, source.replyToAddress, source.senderCode, source.contentMode, subject, textBody, htmlBody,
        source.templateCode, source.templateData, source.status, source.attemptCount, source.retryCycle, source.cycleAttemptCount,
        source.nextAttemptAt, source.acceptedAt, source.deliveredAt, source.failedAt, source.lastErrorCode, source.lastErrorMessage,
        source.createdAt, source.updatedAt, source.claimToken, source.claimedAt, source.leaseUntil, source.workerId);
  }

  private static EmailDelivery base(UUID id, UUID communicationId, String recipientAddress, String recipientName,
       String fromName, String fromAddress, String replyToAddress, String senderCode, EmailContentMode mode, String subject,
      String textBody, String htmlBody, String templateCode, String templateData, Instant now) {
    return new EmailDelivery(id, communicationId, recipientAddress, recipientName, fromName, fromAddress,
        replyToAddress, senderCode, mode, subject, textBody, htmlBody, templateCode, templateData,
        EmailDeliveryStatus.PENDING, 0, 1, 0, null, null, null, null, null, null, now, now,
        null, null, null, null);
  }

  public static EmailDelivery reconstitute(UUID id, UUID communicationId, String recipientAddress, String recipientName,
       String fromName, String fromAddress, String replyToAddress, String senderCode, EmailContentMode mode, String subject,
      String textBody, String htmlBody, String templateCode, String templateData, EmailDeliveryStatus status,
      int attemptCount, Instant nextAttemptAt, Instant acceptedAt, Instant deliveredAt, Instant failedAt,
      String errorCode, String errorMessage, Instant createdAt, Instant updatedAt, UUID claimToken,
      Instant claimedAt, Instant leaseUntil, String workerId, int retryCycle, int cycleAttemptCount) {
    return new EmailDelivery(id, communicationId, recipientAddress, recipientName, fromName, fromAddress,
        replyToAddress, senderCode, mode, subject, textBody, htmlBody, templateCode, templateData, status, attemptCount,
        retryCycle, cycleAttemptCount, nextAttemptAt, acceptedAt, deliveredAt, failedAt, errorCode, errorMessage,
        createdAt, updatedAt, claimToken, claimedAt, leaseUntil, workerId);
  }

  /** Compatibility mapper for legacy rows. */
  public static EmailDelivery reconstitute(UUID id, UUID communicationId, String recipientAddress, String recipientName,
      String fromName, String fromAddress, String replyToAddress, EmailContentMode mode, String subject,
      String textBody, String htmlBody, String templateCode, String templateData, EmailDeliveryStatus status,
      int attemptCount, Instant nextAttemptAt, Instant acceptedAt, Instant deliveredAt, Instant failedAt,
      String errorCode, String errorMessage, Instant createdAt, Instant updatedAt, UUID claimToken, Instant leaseUntil) {
    return reconstitute(id, communicationId, recipientAddress, recipientName, fromName, fromAddress, replyToAddress,
        null, mode, subject, textBody, htmlBody, templateCode, templateData, status, attemptCount, nextAttemptAt,
        acceptedAt, deliveredAt, failedAt, errorCode, errorMessage, createdAt, updatedAt, claimToken, null,
        leaseUntil, null, 1, 0);
  }

  public EmailDelivery accepted(Instant now) { return transition(EmailDeliveryStatus.ACCEPTED, now, null, null, null, null, attemptCount, cycleAttemptCount); }
  public EmailDelivery retryScheduled(String code, String message, Instant next, Instant now) {
    return transition(EmailDeliveryStatus.RETRY_SCHEDULED, now, next, code, message, null, attemptCount, cycleAttemptCount);
  }
  public EmailDelivery failedTerminal(String code, String message, Instant now) {
    return transition(EmailDeliveryStatus.FAILED, now, null, code, message, now, attemptCount, cycleAttemptCount);
  }
  public EmailDelivery indeterminate(String code, String message, Instant now) {
    return transition(EmailDeliveryStatus.INDETERMINATE, now, null, code, message, null, attemptCount, cycleAttemptCount);
  }
  public EmailDelivery retriedByOperator(Instant now) {
    if (status != EmailDeliveryStatus.FAILED) throw new CommunicationsRejectedException(RejectionCode.EMAIL_DELIVERY_NOT_FAILED, "delivery is not FAILED");
    return new EmailDelivery(emailDeliveryId, communicationId, recipientAddress, recipientName, fromName, fromAddress,
        replyToAddress, senderCode, contentMode, subject, textBody, htmlBody, templateCode, templateData, EmailDeliveryStatus.PENDING,
        attemptCount, retryCycle + 1, 0, now, acceptedAt, deliveredAt, null, null, null, createdAt, now,
        null, null, null, null);
  }
  public EmailDelivery resendAuthorized(Instant now) {
    if (status != EmailDeliveryStatus.INDETERMINATE) throw new CommunicationsRejectedException(RejectionCode.EMAIL_DELIVERY_NOT_FAILED, "delivery is not INDETERMINATE");
    return new EmailDelivery(emailDeliveryId, communicationId, recipientAddress, recipientName, fromName, fromAddress,
        replyToAddress, senderCode, contentMode, subject, textBody, htmlBody, templateCode, templateData, EmailDeliveryStatus.PENDING,
        attemptCount, retryCycle + 1, 0, now, acceptedAt, deliveredAt, null, null, null, createdAt, now,
        null, null, null, null);
  }
  public EmailDelivery claimed(UUID token, Instant now, Instant until, String worker) {
    return new EmailDelivery(emailDeliveryId, communicationId, recipientAddress, recipientName, fromName, fromAddress,
        replyToAddress, senderCode, contentMode, subject, textBody, htmlBody, templateCode, templateData, status, attemptCount,
        retryCycle, cycleAttemptCount, nextAttemptAt, acceptedAt, deliveredAt, failedAt, lastErrorCode, lastErrorMessage,
        createdAt, now, token, now, until, worker);
  }
  /** Legacy domain helper retained for callers that model claim and start together. */
  public EmailDelivery claimedForSending(Instant now) {
    EmailDelivery claimed = claimToken == null ? claimed(UUID.randomUUID(), now, now.plusSeconds(30), "legacy-worker") : this;
    return claimed.startedSending(now);
  }
  public EmailDelivery startedSending(Instant now) {
    if (claimToken == null || leaseUntil == null || leaseUntil.isBefore(now))
      throw new IllegalStateException("delivery claim is not active");
    return new EmailDelivery(emailDeliveryId, communicationId, recipientAddress, recipientName, fromName, fromAddress,
        replyToAddress, senderCode, contentMode, subject, textBody, htmlBody, templateCode, templateData, EmailDeliveryStatus.SENDING,
        attemptCount + 1, retryCycle, cycleAttemptCount + 1, nextAttemptAt, acceptedAt, deliveredAt, failedAt,
        lastErrorCode, lastErrorMessage, createdAt, now, claimToken, claimedAt, leaseUntil, workerId);
  }

  private EmailDelivery transition(EmailDeliveryStatus newStatus, Instant now, Instant next, String code, String message,
      Instant failed, int attempts, int cycleAttempts) {
    return new EmailDelivery(emailDeliveryId, communicationId, recipientAddress, recipientName, fromName, fromAddress,
        replyToAddress, senderCode, contentMode, subject, textBody, htmlBody, templateCode, templateData, newStatus, attempts,
        retryCycle, cycleAttempts, next, newStatus == EmailDeliveryStatus.ACCEPTED ? now : acceptedAt, deliveredAt,
        failed, code, message, createdAt, now, null, null, null, null);
  }

  public UUID emailDeliveryId() { return emailDeliveryId; }
  public UUID communicationId() { return communicationId; }
  public String recipientAddress() { return recipientAddress; }
  public String recipientName() { return recipientName; }
  public String fromName() { return fromName; }
  public String fromAddress() { return fromAddress; }
  public String replyToAddress() { return replyToAddress; }
  public String senderCode() { return senderCode; }
  public EmailContentMode contentMode() { return contentMode; }
  public String subject() { return subject; }
  public String textBody() { return textBody; }
  public String htmlBody() { return htmlBody; }
  public String templateCode() { return templateCode; }
  public String templateData() { return templateData; }
  public EmailDeliveryStatus status() { return status; }
  public int attemptCount() { return attemptCount; }
  public int retryCycle() { return retryCycle; }
  public int cycleAttemptCount() { return cycleAttemptCount; }
  public Instant nextAttemptAt() { return nextAttemptAt; }
  public Instant acceptedAt() { return acceptedAt; }
  public Instant deliveredAt() { return deliveredAt; }
  public Instant failedAt() { return failedAt; }
  public String lastErrorCode() { return lastErrorCode; }
  public String lastErrorMessage() { return lastErrorMessage; }
  public Instant createdAt() { return createdAt; }
  public Instant updatedAt() { return updatedAt; }
  public UUID claimToken() { return claimToken; }
  public Instant claimedAt() { return claimedAt; }
  public Instant leaseUntil() { return leaseUntil; }
  public String workerId() { return workerId; }
  public UUID leaseToken() { return claimToken; }
  public Instant leaseExpiresAt() { return leaseUntil; }

  private static <T> T required(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " must not be null"); return value; }
  private static String nonBlank(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank"); return value; }
}

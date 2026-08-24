package com.estoma.communications.domain.communication;

import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationStatus;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Aggregate root for one multi-channel communication request. */
public final class Communication {

  private final UUID communicationId;
  private final UUID recipientAccountId;
  private final String systemCode;
  private final String communicationType;
  private final CommunicationPriority priority;
  private final String sourceService;
  private final String sourceReferenceType;
  private final String sourceReferenceId;
  private final UUID initiatedByAccountId;
  private final CommunicationStatus status;
  private final Instant createdAt;
  private final Instant completedAt;
  private final Instant failedAt;
  private final List<String> requestedChannels;
  private final UUID sourceMessageId;
  private final String idempotencyKey;
  private final String requestFingerprint;
  private final UUID correlationId;
  private final UUID causationId;

  private Communication(
      UUID communicationId,
      UUID recipientAccountId,
      String systemCode,
      String communicationType,
      CommunicationPriority priority,
      String sourceService,
      String sourceReferenceType,
      String sourceReferenceId,
      UUID initiatedByAccountId,
      CommunicationStatus status,
      Instant createdAt,
      Instant completedAt,
      Instant failedAt,
      List<String> requestedChannels,
      UUID sourceMessageId,
      String idempotencyKey,
      String requestFingerprint,
      UUID correlationId,
      UUID causationId) {
    this.communicationId = requireNonNull(communicationId, "communicationId");
    this.recipientAccountId = requireNonNull(recipientAccountId, "recipientAccountId");
    this.systemCode = requireNonBlank(systemCode, "systemCode");
    this.communicationType = requireNonBlank(communicationType, "communicationType");
    this.priority = requireNonNull(priority, "priority");
    this.sourceService = requireNonBlank(sourceService, "sourceService");
    this.sourceReferenceType = requireNonBlank(sourceReferenceType, "sourceReferenceType");
    this.sourceReferenceId = requireNonBlank(sourceReferenceId, "sourceReferenceId");
    this.initiatedByAccountId = initiatedByAccountId;
    this.status = requireNonNull(status, "status");
    this.createdAt = requireNonNull(createdAt, "createdAt");
    this.completedAt = completedAt;
    this.failedAt = failedAt;
    this.requestedChannels = List.copyOf(requireNonNull(requestedChannels, "requestedChannels"));
    this.sourceMessageId = sourceMessageId;
    this.idempotencyKey = idempotencyKey;
    this.requestFingerprint = requestFingerprint;
    this.correlationId = correlationId;
    this.causationId = causationId;
  }

  public static Communication requested(
      UUID communicationId,
      UUID recipientAccountId,
      String systemCode,
      String communicationType,
      CommunicationPriority priority,
      String sourceService,
      String sourceReferenceType,
      String sourceReferenceId,
      UUID initiatedByAccountId,
      Instant now) {
    return new Communication(
        communicationId, recipientAccountId, systemCode, communicationType, priority, sourceService,
        sourceReferenceType, sourceReferenceId, initiatedByAccountId, CommunicationStatus.PROCESSING,
        now, null, null, List.of(), null, null, null, null, null);
  }

  /**
   * The durable key is canonical String on the wire/DB. Object is accepted only at this in-process
   * compatibility boundary so older UUID-based fixtures/adapters can migrate without semantic loss.
   */
  public static Communication requested(
      UUID communicationId,
      UUID recipientAccountId,
      String systemCode,
      String communicationType,
      CommunicationPriority priority,
      String sourceService,
      String sourceReferenceType,
      String sourceReferenceId,
      UUID initiatedByAccountId,
      List<String> requestedChannels,
      UUID sourceMessageId,
      Object idempotencyKey,
      String requestFingerprint,
      UUID correlationId,
      UUID causationId,
      Instant now) {
    return new Communication(
        communicationId, recipientAccountId, systemCode, communicationType, priority, sourceService,
        sourceReferenceType, sourceReferenceId, initiatedByAccountId, CommunicationStatus.PROCESSING,
        now, null, null, requestedChannels, sourceMessageId, normalizeIdempotencyKey(idempotencyKey),
        requestFingerprint, correlationId, causationId);
  }

  public static Communication reconstitute(
      UUID communicationId,
      UUID recipientAccountId,
      String systemCode,
      String communicationType,
      CommunicationPriority priority,
      String sourceService,
      String sourceReferenceType,
      String sourceReferenceId,
      UUID initiatedByAccountId,
      CommunicationStatus status,
      Instant createdAt,
      Instant completedAt,
      Instant failedAt) {
    return new Communication(
        communicationId, recipientAccountId, systemCode, communicationType, priority, sourceService,
        sourceReferenceType, sourceReferenceId, initiatedByAccountId, status, createdAt, completedAt,
        failedAt, List.of(), null, null, null, null, null);
  }

  public static Communication reconstitute(
      UUID communicationId,
      UUID recipientAccountId,
      String systemCode,
      String communicationType,
      CommunicationPriority priority,
      String sourceService,
      String sourceReferenceType,
      String sourceReferenceId,
      UUID initiatedByAccountId,
      CommunicationStatus status,
      Instant createdAt,
      Instant completedAt,
      Instant failedAt,
      List<String> requestedChannels,
      UUID sourceMessageId,
      Object idempotencyKey,
      String requestFingerprint,
      UUID correlationId,
      UUID causationId) {
    return new Communication(
        communicationId, recipientAccountId, systemCode, communicationType, priority, sourceService,
        sourceReferenceType, sourceReferenceId, initiatedByAccountId, status, createdAt, completedAt,
        failedAt, requestedChannels, sourceMessageId, normalizeIdempotencyKey(idempotencyKey),
        requestFingerprint, correlationId, causationId);
  }

  public Communication completed(Instant now) {
    if (status != CommunicationStatus.PROCESSING) {
      throw new CommunicationsRejectedException(
          RejectionCode.COMMUNICATION_ALREADY_EXISTS,
          "communication " + communicationId + " is not in a completable state");
    }
    return copyWith(CommunicationStatus.COMPLETED, now, failedAt, correlationId);
  }

  public Communication failed(Instant now) {
    if (status != CommunicationStatus.PROCESSING) {
      throw new CommunicationsRejectedException(
          RejectionCode.COMMUNICATION_ALREADY_EXISTS,
          "communication " + communicationId + " is not in a failable state");
    }
    return copyWith(CommunicationStatus.FAILED, completedAt, now, correlationId);
  }

  public Communication withCorrelationId(UUID newCorrelationId) {
    if (correlationId != null) return this;
    return copyWith(status, completedAt, failedAt, requireNonNull(newCorrelationId, "correlationId"));
  }

  public Communication reopenedForRetry(Instant now) {
    if (status != CommunicationStatus.FAILED) {
      throw new CommunicationsRejectedException(
          RejectionCode.COMMUNICATION_ALREADY_EXISTS,
          "communication " + communicationId + " is not FAILED");
    }
    return copyWith(CommunicationStatus.PROCESSING, null, null, correlationId);
  }

  public Communication reopenForRetry(Instant now) {
    if (status != CommunicationStatus.FAILED) {
      throw new IllegalStateException("communication is not FAILED");
    }
    return copyWith(CommunicationStatus.PROCESSING, null, null, correlationId);
  }

  private Communication copyWith(
      CommunicationStatus newStatus,
      Instant newCompletedAt,
      Instant newFailedAt,
      UUID newCorrelationId) {
    return new Communication(
        communicationId, recipientAccountId, systemCode, communicationType, priority, sourceService,
        sourceReferenceType, sourceReferenceId, initiatedByAccountId, newStatus, createdAt,
        newCompletedAt, newFailedAt, requestedChannels, sourceMessageId, idempotencyKey,
        requestFingerprint, newCorrelationId, causationId);
  }

  public UUID communicationId() { return communicationId; }
  public UUID recipientAccountId() { return recipientAccountId; }
  public String systemCode() { return systemCode; }
  public String communicationType() { return communicationType; }
  public CommunicationPriority priority() { return priority; }
  public String sourceService() { return sourceService; }
  public String sourceReferenceType() { return sourceReferenceType; }
  public String sourceReferenceId() { return sourceReferenceId; }
  public UUID initiatedByAccountId() { return initiatedByAccountId; }
  public CommunicationStatus status() { return status; }
  public Instant createdAt() { return createdAt; }
  public Instant completedAt() { return completedAt; }
  public Instant failedAt() { return failedAt; }
  public List<String> requestedChannels() { return requestedChannels; }
  public UUID sourceMessageId() { return sourceMessageId; }
  public String idempotencyKey() { return idempotencyKey; }
  public String requestFingerprint() { return requestFingerprint; }
  public UUID correlationId() { return correlationId; }
  public UUID causationId() { return causationId; }

  private static String normalizeIdempotencyKey(Object value) {
    if (value == null) return null;
    if (value instanceof String text) {
      if (text.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
      return text;
    }
    if (value instanceof UUID uuid) return uuid.toString();
    throw new IllegalArgumentException("idempotencyKey must be String or UUID");
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) throw new IllegalArgumentException(fieldName + " must not be null");
    return value;
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}

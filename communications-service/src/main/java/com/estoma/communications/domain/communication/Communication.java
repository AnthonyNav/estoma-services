package com.estoma.communications.domain.communication;

import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationStatus;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import java.time.Instant;
import java.util.UUID;

/**
 * The aggregate root this service owns: one request to deliver a
 * communication via inbox and/or email (system_contract.md "Ownership").
 * Immutable by design, matching {@code authentication-service}'s {@code
 * Account}: every transition returns a new instance.
 *
 * <p>Completion rule (not spelled out verbatim in the platform docs, but
 * derived from README.md §4 "Comunicación Completada" and events.md §2):
 * a communication with no email channel completes synchronously the
 * instant its inbox message is created (there is no further asynchronous
 * step for the inbox channel). A communication with an email channel stays
 * {@code PROCESSING} until the email delivery worker marks the email
 * {@code ACCEPTED} (-&gt; {@link #completed}) or terminally {@code FAILED}
 * (-&gt; {@link #failed}) -- see {@code RequestCommunicationService} and
 * {@code EmailDeliveryWorker}.
 */
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
      Instant failedAt) {
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
  }

  /**
   * A new communication request, already validated. Status starts {@code
   * PROCESSING} directly (not {@code PENDING}): validation and creation
   * happen atomically in {@code RequestCommunicationHandler}, so there is
   * no separate "not yet validated" window (events.md §4's {@code
   * CommunicationRequested} payload documents {@code status} as {@code
   * PROCESSING} at the moment of the event).
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
      Instant now) {
    return new Communication(
        communicationId, recipientAccountId, systemCode, communicationType, priority, sourceService,
        sourceReferenceType, sourceReferenceId, initiatedByAccountId, CommunicationStatus.PROCESSING, now, null, null);
  }

  /** Reconstructs a Communication from a persisted row. */
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
        sourceReferenceType, sourceReferenceId, initiatedByAccountId, status, createdAt, completedAt, failedAt);
  }

  /** Every requested channel reached its terminal successful state. */
  public Communication completed(Instant now) {
    if (status != CommunicationStatus.PROCESSING) {
      throw new CommunicationsRejectedException(
          RejectionCode.COMMUNICATION_ALREADY_EXISTS, "communication " + communicationId + " is not in a completable state");
    }
    return new Communication(
        communicationId, recipientAccountId, systemCode, communicationType, priority, sourceService,
        sourceReferenceType, sourceReferenceId, initiatedByAccountId, CommunicationStatus.COMPLETED, createdAt, now, failedAt);
  }

  /** A required channel failed definitively (commands.md §4, events.md §2 "CommunicationFailed"). */
  public Communication failed(Instant now) {
    if (status != CommunicationStatus.PROCESSING) {
      throw new CommunicationsRejectedException(
          RejectionCode.COMMUNICATION_ALREADY_EXISTS, "communication " + communicationId + " is not in a failable state");
    }
    return new Communication(
        communicationId, recipientAccountId, systemCode, communicationType, priority, sourceService,
        sourceReferenceType, sourceReferenceId, initiatedByAccountId, CommunicationStatus.FAILED, createdAt, completedAt, now);
  }

  public UUID communicationId() {
    return communicationId;
  }

  public UUID recipientAccountId() {
    return recipientAccountId;
  }

  public String systemCode() {
    return systemCode;
  }

  public String communicationType() {
    return communicationType;
  }

  public CommunicationPriority priority() {
    return priority;
  }

  public String sourceService() {
    return sourceService;
  }

  public String sourceReferenceType() {
    return sourceReferenceType;
  }

  public String sourceReferenceId() {
    return sourceReferenceId;
  }

  public UUID initiatedByAccountId() {
    return initiatedByAccountId;
  }

  public CommunicationStatus status() {
    return status;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant completedAt() {
    return completedAt;
  }

  public Instant failedAt() {
    return failedAt;
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

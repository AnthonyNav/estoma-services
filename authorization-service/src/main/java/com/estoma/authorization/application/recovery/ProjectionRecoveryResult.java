package com.estoma.authorization.application.recovery;

import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Transport-neutral Result consumed by the projection recovery state machine. */
public record ProjectionRecoveryResult(
    Metadata metadata,
    Status status,
    List<SnapshotItem> items,
    String nextCursor,
    boolean hasMore,
    String errorCode) {

  public ProjectionRecoveryResult {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(status, "status");
    items = List.copyOf(items == null ? List.of() : items);
    if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
      throw new IllegalArgumentException("hasMore requires nextCursor");
    }
    if ((status == Status.REJECTED || status == Status.FAILED)
        && (errorCode == null || errorCode.isBlank())) {
      throw new IllegalArgumentException("terminal error result requires errorCode");
    }
  }

  public enum Status {
    FOUND,
    NOT_FOUND,
    SUCCEEDED,
    REJECTED,
    FAILED
  }

  public record Metadata(
      UUID messageId,
      String messageType,
      String producer,
      Instant occurredAt,
      UUID correlationId,
      UUID causationId,
      UUID operationId,
      ActorRef actor) {
    public Metadata {
      Objects.requireNonNull(messageId, "messageId");
      requireText(messageType, "messageType");
      requireText(producer, "producer");
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(correlationId, "correlationId");
      Objects.requireNonNull(causationId, "causationId");
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(actor, "actor");
    }
  }

  public sealed interface SnapshotItem permits PersonSnapshot, AccountSnapshot, EligibilitySnapshot {}

  public record PersonSnapshot(
      UUID personId, ProjectedStatus status, int sourceVersion, Instant updatedAt)
      implements SnapshotItem {
    public PersonSnapshot {
      Objects.requireNonNull(personId, "personId");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(updatedAt, "updatedAt");
      requirePositive(sourceVersion, "sourceVersion");
    }
  }

  public record AccountSnapshot(
      UUID accountId,
      UUID personId,
      LoginType loginType,
      ProjectedStatus status,
      int sourceVersion,
      Instant updatedAt)
      implements SnapshotItem {
    public AccountSnapshot {
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(personId, "personId");
      Objects.requireNonNull(loginType, "loginType");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(updatedAt, "updatedAt");
      requirePositive(sourceVersion, "sourceVersion");
    }
  }

  public record EligibilitySnapshot(
      UUID eligibilityId,
      UUID studentAcademicProfileId,
      UUID accountId,
      String systemCode,
      boolean eligible,
      int policyVersion,
      int eligibilityVersion,
      int academicProfileVersion,
      Instant evaluatedAt,
      Instant updatedAt)
      implements SnapshotItem {
    public EligibilitySnapshot {
      Objects.requireNonNull(eligibilityId, "eligibilityId");
      Objects.requireNonNull(studentAcademicProfileId, "studentAcademicProfileId");
      Objects.requireNonNull(accountId, "accountId");
      requireText(systemCode, "systemCode");
      Objects.requireNonNull(evaluatedAt, "evaluatedAt");
      Objects.requireNonNull(updatedAt, "updatedAt");
      requirePositive(policyVersion, "policyVersion");
      requirePositive(eligibilityVersion, "eligibilityVersion");
      requirePositive(academicProfileVersion, "academicProfileVersion");
    }
  }

  private static void requirePositive(int value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank");
    }
  }
}

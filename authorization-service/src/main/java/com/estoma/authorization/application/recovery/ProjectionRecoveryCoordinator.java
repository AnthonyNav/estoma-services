package com.estoma.authorization.application.recovery;

import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.AcademicEligibilityProjectionRepository;
import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository.RecoveryJob;
import com.estoma.authorization.application.port.TransactionRunner;
import com.estoma.authorization.application.support.CanonicalJson;
import com.estoma.authorization.application.support.OutboxIntentWriter;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.SystemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable, broker-agnostic coordinator for ENTITY and FULL projection recovery. */
public final class ProjectionRecoveryCoordinator {

  public static final int DEFAULT_PAGE_SIZE = 200;

  private final TransactionRunner transactions;
  private final ProjectionRecoveryJobRepository jobs;
  private final PersonStatusProjectionRepository persons;
  private final AccountStatusProjectionRepository accounts;
  private final AcademicEligibilityProjectionRepository eligibility;
  private final OutboxRepository outbox;
  private final Clock clock;

  public ProjectionRecoveryCoordinator(
      TransactionRunner transactions,
      ProjectionRecoveryJobRepository jobs,
      PersonStatusProjectionRepository persons,
      AccountStatusProjectionRepository accounts,
      AcademicEligibilityProjectionRepository eligibility,
      OutboxRepository outbox,
      Clock clock) {
    this.transactions = transactions;
    this.jobs = jobs;
    this.persons = persons;
    this.accounts = accounts;
    this.eligibility = eligibility;
    this.outbox = outbox;
    this.clock = clock;
  }

  public RecoveryJob requestPerson(UUID personId) {
    Objects.requireNonNull(personId, "personId");
    return requestEntity(ProjectionType.PERSON_STATUS, "person:" + personId);
  }

  public RecoveryJob requestAccount(UUID accountId) {
    Objects.requireNonNull(accountId, "accountId");
    return requestEntity(ProjectionType.ACCOUNT_STATUS, "account:" + accountId);
  }

  public RecoveryJob requestEligibility(UUID studentAcademicProfileId, String systemCode) {
    Objects.requireNonNull(studentAcademicProfileId, "studentAcademicProfileId");
    if (!SystemCode.PRACTICAS_PROFESIONALES.name().equals(systemCode)) {
      throw new IllegalArgumentException("Authorization V1 only recovers Practices eligibility");
    }
    return requestEntity(
        ProjectionType.ACADEMIC_ELIGIBILITY,
        "eligibility:" + studentAcademicProfileId + ":" + systemCode);
  }

  public RecoveryJob requestFull(ProjectionType projectionType) {
    Objects.requireNonNull(projectionType, "projectionType");
    return transactions.inTransaction(() -> {
      var existing = jobs.findActive(projectionType.name(), "FULL", null);
      if (existing.isPresent()) {
        return existing.get();
      }
      Instant now = clock.instant();
      RecoveryJob pending = pendingJob(projectionType, "FULL", null, now);
      if (!jobs.insertIfAbsent(pending)) {
        return jobs.findActive(projectionType.name(), "FULL", null)
            .orElseThrow(() -> new IllegalStateException("active FULL recovery disappeared"));
      }
      clearProjection(projectionType);
      return issueInTransaction(pending);
    });
  }

  public RecoveryJob retry(UUID recoveryJobId) {
    Objects.requireNonNull(recoveryJobId, "recoveryJobId");
    return transactions.inTransaction(() -> {
      RecoveryJob waiting = jobs.findById(recoveryJobId)
          .orElseThrow(() -> new IllegalArgumentException("recovery job not found"));
      if (!"WAITING_RESULT".equals(waiting.status())) {
        throw new IllegalStateException("only WAITING_RESULT recovery can be retried");
      }
      Instant now = clock.instant();
      RecoveryJob pending = new RecoveryJob(
          waiting.recoveryJobId(),
          waiting.projectionType(),
          waiting.scope(),
          waiting.entityKey(),
          "PENDING",
          waiting.cursor(),
          waiting.queryAttempt(),
          null,
          null,
          null,
          null,
          waiting.attempts(),
          waiting.lastErrorCode(),
          waiting.createdAt(),
          now,
          null,
          null,
          null);
      if (jobs.update(pending, "WAITING_RESULT", waiting.currentQueryMessageId()) != 1) {
        throw new IllegalStateException("recovery job changed concurrently");
      }
      return issueInTransaction(pending);
    });
  }

  public RecoveryJob issueNextPage(RecoveryJob waiting, String nextCursor) {
    Objects.requireNonNull(waiting, "waiting");
    if (!"FULL".equals(waiting.scope()) || !"WAITING_RESULT".equals(waiting.status())) {
      throw new IllegalArgumentException("next page requires waiting FULL recovery");
    }
    if (nextCursor == null || nextCursor.isBlank()) {
      throw new IllegalArgumentException("nextCursor is required");
    }
    Instant now = clock.instant();
    RecoveryJob pending = new RecoveryJob(
        waiting.recoveryJobId(),
        waiting.projectionType(),
        waiting.scope(),
        null,
        "PENDING",
        nextCursor,
        waiting.queryAttempt(),
        null,
        null,
        null,
        null,
        waiting.attempts(),
        null,
        waiting.createdAt(),
        now,
        null,
        null,
        null);
    if (jobs.update(pending, "WAITING_RESULT", waiting.currentQueryMessageId()) != 1) {
      throw new IllegalStateException("recovery page changed concurrently");
    }
    return issueInTransaction(pending);
  }

  private RecoveryJob requestEntity(ProjectionType projectionType, String entityKey) {
    return transactions.inTransaction(() -> {
      var existing = jobs.findActive(projectionType.name(), "ENTITY", entityKey);
      if (existing.isPresent()) {
        return existing.get();
      }
      Instant now = clock.instant();
      RecoveryJob pending = pendingJob(projectionType, "ENTITY", entityKey, now);
      if (!jobs.insertIfAbsent(pending)) {
        return jobs.findActive(projectionType.name(), "ENTITY", entityKey)
            .orElseThrow(() -> new IllegalStateException("active ENTITY recovery disappeared"));
      }
      return issueInTransaction(pending);
    });
  }

  private RecoveryJob issueInTransaction(RecoveryJob pending) {
    if (!"PENDING".equals(pending.status())) {
      throw new IllegalArgumentException("only PENDING recovery can emit a Query");
    }
    ProjectionType projectionType = ProjectionType.valueOf(pending.projectionType());
    Instant now = clock.instant();
    UUID queryMessageId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    OutboundQueryIntent query = queryIntent(
        pending, projectionType, queryMessageId, operationId, correlationId, now);
    OutboxIntentWriter.append(outbox, query);

    RecoveryJob waiting = new RecoveryJob(
        pending.recoveryJobId(),
        pending.projectionType(),
        pending.scope(),
        pending.entityKey(),
        "WAITING_RESULT",
        pending.cursor(),
        pending.queryAttempt() + 1,
        query.messageType(),
        queryMessageId,
        operationId,
        correlationId,
        pending.attempts() + 1,
        null,
        pending.createdAt(),
        now,
        null,
        null,
        null);
    if (jobs.update(waiting, "PENDING", null) != 1) {
      throw new IllegalStateException("recovery job changed before Query could be persisted");
    }
    return waiting;
  }

  private OutboundQueryIntent queryIntent(
      RecoveryJob job,
      ProjectionType projectionType,
      UUID messageId,
      UUID operationId,
      UUID correlationId,
      Instant now) {
    boolean full = "FULL".equals(job.scope());
    String messageType;
    String routingKey;
    Map<String, Object> payload = new LinkedHashMap<>();
    switch (projectionType) {
      case PERSON_STATUS -> {
        routingKey = "users";
        messageType = full ? "ListPersonStatusSnapshotsPage" : "GetPersonStatusSnapshot";
        if (!full) {
          payload.put("personId", parseEntityUuid(job.entityKey(), "person:"));
        }
      }
      case ACCOUNT_STATUS -> {
        routingKey = "authentication";
        messageType = full ? "ListAccountStatusSnapshotsPage" : "GetAccountStatusSnapshot";
        if (!full) {
          payload.put("accountId", parseEntityUuid(job.entityKey(), "account:"));
        }
      }
      case ACADEMIC_ELIGIBILITY -> {
        routingKey = "academic";
        messageType = full
            ? "ListStudentAcademicEligibilitySnapshotsPage"
            : "GetStudentAcademicEligibilitySnapshot";
        if (full) {
          payload.put("systemCode", SystemCode.PRACTICAS_PROFESIONALES);
        } else {
          EligibilityKey key = parseEligibilityKey(job.entityKey());
          payload.put("studentAcademicProfileId", key.studentAcademicProfileId());
          payload.put("systemCode", key.systemCode());
        }
      }
      default -> throw new IllegalStateException("unsupported projection type");
    }
    if (full) {
      if (job.cursor() != null) {
        payload.put("cursor", job.cursor());
      }
      payload.put("limit", DEFAULT_PAGE_SIZE);
    }
    return new OutboundQueryIntent(
        messageId,
        messageType,
        1,
        "authorization",
        routingKey,
        operationId,
        ActorRef.system("authorization"),
        correlationId,
        null,
        CanonicalJson.write(payload),
        now);
  }

  private void clearProjection(ProjectionType projectionType) {
    switch (projectionType) {
      case PERSON_STATUS -> persons.clearAll();
      case ACCOUNT_STATUS -> accounts.clearAll();
      case ACADEMIC_ELIGIBILITY -> eligibility.clearAll();
    }
  }

  private static RecoveryJob pendingJob(
      ProjectionType projectionType, String scope, String entityKey, Instant now) {
    return new RecoveryJob(
        UUID.randomUUID(),
        projectionType.name(),
        scope,
        entityKey,
        "PENDING",
        null,
        0,
        null,
        null,
        null,
        null,
        0,
        null,
        now,
        now,
        null,
        null,
        null);
  }

  private static UUID parseEntityUuid(String entityKey, String prefix) {
    if (entityKey == null || !entityKey.startsWith(prefix)) {
      throw new IllegalStateException("invalid recovery entity key: " + entityKey);
    }
    return UUID.fromString(entityKey.substring(prefix.length()));
  }

  private static EligibilityKey parseEligibilityKey(String entityKey) {
    String prefix = "eligibility:";
    if (entityKey == null || !entityKey.startsWith(prefix)) {
      throw new IllegalStateException("invalid eligibility recovery key");
    }
    String remainder = entityKey.substring(prefix.length());
    int separator = remainder.indexOf(':');
    if (separator <= 0 || separator == remainder.length() - 1) {
      throw new IllegalStateException("invalid eligibility recovery key");
    }
    return new EligibilityKey(
        UUID.fromString(remainder.substring(0, separator)), remainder.substring(separator + 1));
  }

  public enum ProjectionType {
    PERSON_STATUS,
    ACCOUNT_STATUS,
    ACADEMIC_ELIGIBILITY
  }

  private record EligibilityKey(UUID studentAcademicProfileId, String systemCode) {}
}

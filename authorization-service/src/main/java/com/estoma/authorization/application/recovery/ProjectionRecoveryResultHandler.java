package com.estoma.authorization.application.recovery;

import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.AcademicEligibilityProjectionRepository;
import com.estoma.authorization.application.port.DeferredProjectionEventDecoder;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository.RecoveryJob;
import com.estoma.authorization.application.port.TransactionRunner;
import com.estoma.authorization.application.projection.PracticesProvisioningService;
import com.estoma.authorization.application.projection.ProjectionEvent;
import com.estoma.authorization.application.projection.ProjectionEventProcessor;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator.ProjectionType;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResult.AccountSnapshot;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResult.EligibilitySnapshot;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResult.PersonSnapshot;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResult.SnapshotItem;
import com.estoma.authorization.domain.model.SystemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Applies authoritative snapshot Results and wakes dependent durable work only after recovery commits. */
public final class ProjectionRecoveryResultHandler {

  private static final String RESULT_CONSUMER = "authorization-recovery-results";
  private static final String EVENT_CONSUMER = "authorization-events";
  private static final int DEFERRED_DRAIN_LIMIT = 1000;

  private final TransactionRunner transactions;
  private final ProjectionRecoveryJobRepository jobs;
  private final ProcessedMessageRepository inbox;
  private final PersonStatusProjectionRepository persons;
  private final AccountStatusProjectionRepository accounts;
  private final AcademicEligibilityProjectionRepository eligibility;
  private final ProjectionRecoveryCoordinator coordinator;
  private final PracticesProvisioningService provisioning;
  private final DeferredProjectionEventDecoder deferredEventDecoder;
  private final DeferredCommandRecoveryService deferredCommands;
  private final ProjectionEventProcessor projectionEvents;
  private final Clock clock;

  public ProjectionRecoveryResultHandler(
      TransactionRunner transactions,
      ProjectionRecoveryJobRepository jobs,
      ProcessedMessageRepository inbox,
      PersonStatusProjectionRepository persons,
      AccountStatusProjectionRepository accounts,
      AcademicEligibilityProjectionRepository eligibility,
      ProjectionRecoveryCoordinator coordinator,
      PracticesProvisioningService provisioning,
      DeferredProjectionEventDecoder deferredEventDecoder,
      DeferredCommandRecoveryService deferredCommands,
      ProjectionEventProcessor projectionEvents,
      Clock clock) {
    this.transactions = transactions;
    this.jobs = jobs;
    this.inbox = inbox;
    this.persons = persons;
    this.accounts = accounts;
    this.eligibility = eligibility;
    this.coordinator = coordinator;
    this.provisioning = provisioning;
    this.deferredEventDecoder = deferredEventDecoder;
    this.deferredCommands = deferredCommands;
    this.projectionEvents = projectionEvents;
    this.clock = clock;
  }

  public HandlingResult handle(ProjectionRecoveryResult result) {
    HandlingResult handling = transactions.inTransaction(() -> handleInTransaction(result));
    afterCommittedRecovery(result, handling);
    return handling;
  }

  private HandlingResult handleInTransaction(ProjectionRecoveryResult result) {
    Instant now = clock.instant();
    var metadata = result.metadata();
    Optional<ProcessedMessageRepository.ProcessedMessage> existingInbox =
        inbox.find(RESULT_CONSUMER, metadata.messageId());
    if (existingInbox.isPresent() && "APPLIED".equals(existingInbox.get().status())) {
      return new HandlingResult(Status.DUPLICATE, null);
    }
    if (existingInbox.isEmpty()) {
      inbox.insertIfAbsent(new ProcessedMessageRepository.ProcessedMessage(
          RESULT_CONSUMER,
          metadata.messageId(),
          "DEFERRED",
          metadata.messageType(),
          1,
          "RESULT",
          metadata.producer(),
          metadata.correlationId(),
          metadata.causationId(),
          metadata.operationId(),
          null,
          null,
          null,
          metadata.actor(),
          "{}",
          now,
          metadata.occurredAt(),
          null,
          "PROCESSING",
          now));
    }

    Optional<RecoveryJob> maybeJob = jobs.findWaitingByQueryMessageId(metadata.causationId());
    if (maybeJob.isEmpty()) {
      inbox.markApplied(RESULT_CONSUMER, metadata.messageId(), now, now);
      return new HandlingResult(Status.STALE_RESULT, null);
    }
    RecoveryJob job = maybeJob.get();
    validateResultEnvelope(result, job);

    if (result.status() == ProjectionRecoveryResult.Status.REJECTED
        || result.status() == ProjectionRecoveryResult.Status.FAILED) {
      RecoveryJob failed = terminalJob(job, "FAILED", result.errorCode(), now);
      updateWaitingJob(failed, job);
      inbox.markApplied(RESULT_CONSUMER, metadata.messageId(), now, now);
      return new HandlingResult(Status.FAILED, failed.recoveryJobId());
    }

    ProjectionType projectionType = ProjectionType.valueOf(job.projectionType());
    if ("ENTITY".equals(job.scope())) {
      HandlingResult entityResult = handleEntity(result, job, projectionType, now);
      inbox.markApplied(RESULT_CONSUMER, metadata.messageId(), now, now);
      return entityResult;
    }

    if (result.status() != ProjectionRecoveryResult.Status.SUCCEEDED) {
      throw new IllegalArgumentException("FULL recovery requires SUCCEEDED Result");
    }
    validateItemTypes(result, projectionType);
    applyItems(result, projectionType);

    RecoveryJob updated;
    if (result.hasMore()) {
      updated = coordinator.issueNextPage(job, result.nextCursor());
    } else {
      updated = terminalJob(job, "COMPLETED", null, now);
      updateWaitingJob(updated, job);
    }
    inbox.markApplied(RESULT_CONSUMER, metadata.messageId(), now, now);
    return new HandlingResult(
        result.hasMore() ? Status.NEXT_PAGE_REQUESTED : Status.COMPLETED,
        updated.recoveryJobId());
  }

  private HandlingResult handleEntity(
      ProjectionRecoveryResult result,
      RecoveryJob job,
      ProjectionType projectionType,
      Instant now) {
    if (result.status() == ProjectionRecoveryResult.Status.NOT_FOUND) {
      if (projectionExists(job, projectionType)) {
        RecoveryJob failed = terminalJob(job, "FAILED", "NOT_FOUND_CONFLICT", now);
        updateWaitingJob(failed, job);
        return new HandlingResult(Status.NOT_FOUND_CONFLICT, failed.recoveryJobId());
      }
      RecoveryJob completed = terminalJob(job, "COMPLETED", null, now);
      updateWaitingJob(completed, job);
      return new HandlingResult(Status.NOT_FOUND_CONFIRMED, completed.recoveryJobId());
    }
    if (result.status() != ProjectionRecoveryResult.Status.FOUND || result.items().size() != 1) {
      throw new IllegalArgumentException("ENTITY recovery requires FOUND with one item or NOT_FOUND");
    }
    validateItemTypes(result, projectionType);
    validateEntityIdentity(job, projectionType, result.items().get(0));
    applyItems(result, projectionType);
    RecoveryJob completed = terminalJob(job, "COMPLETED", null, now);
    updateWaitingJob(completed, job);
    return new HandlingResult(Status.COMPLETED, completed.recoveryJobId());
  }

  private static void validateEntityIdentity(
      RecoveryJob job, ProjectionType projectionType, SnapshotItem item) {
    boolean matches = switch (projectionType) {
      case PERSON_STATUS ->
          ((PersonSnapshot) item).personId().equals(parseUuid(job.entityKey(), "person:"));
      case ACCOUNT_STATUS ->
          ((AccountSnapshot) item).accountId().equals(parseUuid(job.entityKey(), "account:"));
      case ACADEMIC_ELIGIBILITY -> {
        EligibilityKey key = parseEligibilityKey(job.entityKey());
        EligibilitySnapshot snapshot = (EligibilitySnapshot) item;
        yield snapshot.studentAcademicProfileId().equals(key.profileId())
            && snapshot.systemCode().equals(key.systemCode());
      }
    };
    if (!matches) {
      throw new IllegalArgumentException(
          "ENTITY recovery snapshot identity does not match requested entityKey");
    }
  }

  private void applyItems(ProjectionRecoveryResult result, ProjectionType projectionType) {
    for (SnapshotItem item : result.items()) {
      switch (projectionType) {
        case PERSON_STATUS -> {
          PersonSnapshot person = (PersonSnapshot) item;
          persons.upsertIfNewer(new PersonStatusProjectionRepository.PersonStatusProjection(
              person.personId(), person.status(), person.sourceVersion(), person.updatedAt()));
        }
        case ACCOUNT_STATUS -> {
          AccountSnapshot account = (AccountSnapshot) item;
          accounts.upsertIfNewer(new AccountStatusProjectionRepository.AccountStatusProjection(
              account.accountId(),
              account.personId(),
              account.loginType(),
              account.status(),
              account.sourceVersion(),
              account.updatedAt()));
        }
        case ACADEMIC_ELIGIBILITY -> {
          EligibilitySnapshot itemEligibility = (EligibilitySnapshot) item;
          eligibility.upsertIfNewer(
              new AcademicEligibilityProjectionRepository.AcademicEligibilityProjection(
                  itemEligibility.eligibilityId(),
                  itemEligibility.studentAcademicProfileId(),
                  itemEligibility.systemCode(),
                  itemEligibility.accountId(),
                  itemEligibility.eligible(),
                  itemEligibility.policyVersion(),
                  itemEligibility.eligibilityVersion(),
                  itemEligibility.academicProfileVersion(),
                  null,
                  itemEligibility.evaluatedAt(),
                  itemEligibility.updatedAt()));
        }
      }
    }
  }

  private void afterCommittedRecovery(
      ProjectionRecoveryResult result, HandlingResult handling) {
    if (handling.recoveryJobId() == null
        || (handling.status() != Status.COMPLETED
            && handling.status() != Status.NOT_FOUND_CONFIRMED)) {
      return;
    }
    RecoveryJob job = jobs.findById(handling.recoveryJobId())
        .orElseThrow(() -> new IllegalStateException("completed recovery job disappeared"));
    ProjectionType projectionType = ProjectionType.valueOf(job.projectionType());
    if ("FULL".equals(job.scope())) {
      wakeAfterFull(result, projectionType);
      return;
    }
    wakeAfterEntity(result, job, projectionType);
  }

  private void wakeAfterEntity(
      ProjectionRecoveryResult result,
      RecoveryJob job,
      ProjectionType projectionType) {
    boolean notFound = result.status() == ProjectionRecoveryResult.Status.NOT_FOUND;
    switch (projectionType) {
      case PERSON_STATUS -> {
        UUID personId = parseUuid(job.entityKey(), "person:");
        wakeDeferredEvents(personId, notFound);
      }
      case ACCOUNT_STATUS -> {
        UUID accountId = parseUuid(job.entityKey(), "account:");
        wakeDeferredEvents(accountId, notFound);
        deferredCommands.replayForAccount(
            accountId, "PROJECTION_RECONCILIATION", notFound);
        if (!notFound) {
          evaluateProvisioning(accountId, result);
        }
      }
      case ACADEMIC_ELIGIBILITY -> {
        if (!notFound && !result.items().isEmpty()) {
          EligibilitySnapshot item = (EligibilitySnapshot) result.items().get(0);
          evaluateProvisioning(item.accountId(), result);
        }
      }
    }
  }

  private void wakeAfterFull(
      ProjectionRecoveryResult result, ProjectionType projectionType) {
    for (ProcessedMessageRepository.ProcessedMessage deferred :
        inbox.findDeferred(EVENT_CONSUMER, "EVENT", DEFERRED_DRAIN_LIMIT)) {
      boolean belongsToProjection = switch (projectionType) {
        case PERSON_STATUS -> "users".equals(deferred.producer());
        case ACCOUNT_STATUS -> "authentication".equals(deferred.producer());
        case ACADEMIC_ELIGIBILITY -> false;
      };
      if (belongsToProjection) {
        projectionEvents.replayDeferred(deferredEventDecoder.decode(deferred));
      }
    }
    if (projectionType == ProjectionType.ACCOUNT_STATUS) {
      deferredCommands.drainProjectionReconciliation();
    }
    if (projectionType == ProjectionType.ACCOUNT_STATUS
        || projectionType == ProjectionType.ACADEMIC_ELIGIBILITY) {
      for (UUID accountId : eligibility.findEligibleAccountIds(
          SystemCode.PRACTICAS_PROFESIONALES.name())) {
        evaluateProvisioning(accountId, result);
      }
    }
  }

  private void wakeDeferredEvents(UUID aggregateId, boolean authoritativeNotFound) {
    for (ProcessedMessageRepository.ProcessedMessage deferred :
        inbox.findDeferredByAggregate(EVENT_CONSUMER, aggregateId, DEFERRED_DRAIN_LIMIT)) {
      if (authoritativeNotFound) {
        Instant now = clock.instant();
        transactions.inTransaction(() -> {
          inbox.markApplied(EVENT_CONSUMER, deferred.messageId(), now, now);
          return null;
        });
      } else {
        ProjectionEvent decoded = deferredEventDecoder.decode(deferred);
        projectionEvents.replayDeferred(decoded);
      }
    }
  }

  private void evaluateProvisioning(UUID accountId, ProjectionRecoveryResult result) {
    var metadata = result.metadata();
    provisioning.evaluate(
        accountId,
        new PracticesProvisioningService.Trigger(
            metadata.messageId(),
            metadata.producer(),
            metadata.correlationId(),
            metadata.causationId(),
            metadata.operationId(),
            null,
            null,
            null,
            metadata.occurredAt()));
  }

  private boolean projectionExists(RecoveryJob job, ProjectionType projectionType) {
    return switch (projectionType) {
      case PERSON_STATUS -> persons.findByPersonId(parseUuid(job.entityKey(), "person:")).isPresent();
      case ACCOUNT_STATUS -> accounts.findByAccountId(parseUuid(job.entityKey(), "account:")).isPresent();
      case ACADEMIC_ELIGIBILITY -> {
        EligibilityKey key = parseEligibilityKey(job.entityKey());
        yield eligibility.findByStudentProfileAndSystem(key.profileId(), key.systemCode()).isPresent();
      }
    };
  }

  private void validateResultEnvelope(ProjectionRecoveryResult result, RecoveryJob job) {
    var metadata = result.metadata();
    String expectedProducer = switch (ProjectionType.valueOf(job.projectionType())) {
      case PERSON_STATUS -> "users";
      case ACCOUNT_STATUS -> "authentication";
      case ACADEMIC_ELIGIBILITY -> "academic";
    };
    if (!expectedProducer.equals(metadata.producer())) {
      throw new IllegalArgumentException("recovery Result producer does not own projection");
    }
    if (!metadata.causationId().equals(job.currentQueryMessageId())
        || !metadata.operationId().equals(job.currentOperationId())
        || !metadata.correlationId().equals(job.currentCorrelationId())) {
      throw new IllegalArgumentException("recovery Result does not match current Query attempt");
    }
    if (!metadata.messageType().equals(job.currentQueryType() + "Result")) {
      throw new IllegalArgumentException("recovery Result messageType does not match Query");
    }
    if (metadata.actor().type() != com.estoma.authorization.domain.model.ActorType.SYSTEM
        || !expectedProducer.equals(metadata.actor().service())) {
      throw new IllegalArgumentException("recovery Result actor is not the authoritative owner");
    }
  }

  private static void validateItemTypes(
      ProjectionRecoveryResult result, ProjectionType projectionType) {
    for (SnapshotItem item : result.items()) {
      boolean valid = switch (projectionType) {
        case PERSON_STATUS -> item instanceof PersonSnapshot;
        case ACCOUNT_STATUS -> item instanceof AccountSnapshot;
        case ACADEMIC_ELIGIBILITY -> item instanceof EligibilitySnapshot eligibilityItem
            && SystemCode.PRACTICAS_PROFESIONALES.name().equals(eligibilityItem.systemCode());
      };
      if (!valid) {
        throw new IllegalArgumentException("snapshot item does not match recovery projection type");
      }
    }
  }

  private void updateWaitingJob(RecoveryJob replacement, RecoveryJob waiting) {
    if (jobs.update(replacement, "WAITING_RESULT", waiting.currentQueryMessageId()) != 1) {
      throw new IllegalStateException("recovery job changed concurrently");
    }
  }

  private static RecoveryJob terminalJob(
      RecoveryJob waiting, String status, String errorCode, Instant now) {
    return new RecoveryJob(
        waiting.recoveryJobId(),
        waiting.projectionType(),
        waiting.scope(),
        waiting.entityKey(),
        status,
        waiting.cursor(),
        waiting.queryAttempt(),
        null,
        null,
        null,
        null,
        waiting.attempts(),
        errorCode,
        waiting.createdAt(),
        now,
        now,
        null,
        null);
  }

  private static UUID parseUuid(String entityKey, String prefix) {
    if (entityKey == null || !entityKey.startsWith(prefix)) {
      throw new IllegalStateException("invalid recovery entity key");
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

  public enum Status {
    DUPLICATE,
    STALE_RESULT,
    NEXT_PAGE_REQUESTED,
    COMPLETED,
    NOT_FOUND_CONFIRMED,
    NOT_FOUND_CONFLICT,
    FAILED
  }

  public record HandlingResult(Status status, UUID recoveryJobId) {}

  private record EligibilityKey(UUID profileId, String systemCode) {}
}

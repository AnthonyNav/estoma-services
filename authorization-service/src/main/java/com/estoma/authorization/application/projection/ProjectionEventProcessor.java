package com.estoma.authorization.application.projection;

import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.AcademicEligibilityProjectionRepository;
import com.estoma.authorization.application.port.AuthorizationAuditRepository;
import com.estoma.authorization.application.port.DeferredProjectionEventDecoder;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.TransactionRunner;
import com.estoma.authorization.application.recovery.DeferredCommandRecoveryService;
import com.estoma.authorization.application.support.CanonicalJson;
import com.estoma.authorization.domain.model.ProjectedStatus;
import com.estoma.authorization.domain.model.SystemCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Applies external projection events without trusting transport code to decide causal validity. */
public final class ProjectionEventProcessor {

  private static final String CONSUMER = "authorization-events";
  private static final int DEFERRED_DRAIN_LIMIT = 500;
  private static final Duration REPLAY_LEASE = Duration.ofSeconds(30);
  private final TransactionRunner transactions;
  private final ProcessedMessageRepository inbox;
  private final PersonStatusProjectionRepository persons;
  private final AccountStatusProjectionRepository accounts;
  private final AcademicEligibilityProjectionRepository eligibility;
  private final PracticesProvisioningService provisioning;
  private final DeferredProjectionEventDecoder deferredEventDecoder;
  private final DeferredCommandRecoveryService deferredCommands;
  private final AuthorizationAuditRepository audit;
  private final Clock clock;

  public ProjectionEventProcessor(
      TransactionRunner transactions,
      ProcessedMessageRepository inbox,
      PersonStatusProjectionRepository persons,
      AccountStatusProjectionRepository accounts,
      AcademicEligibilityProjectionRepository eligibility,
      PracticesProvisioningService provisioning,
      DeferredProjectionEventDecoder deferredEventDecoder,
      DeferredCommandRecoveryService deferredCommands,
      AuthorizationAuditRepository audit,
      Clock clock) {
    this.transactions = transactions;
    this.inbox = inbox;
    this.persons = persons;
    this.accounts = accounts;
    this.eligibility = eligibility;
    this.provisioning = provisioning;
    this.deferredEventDecoder = deferredEventDecoder;
    this.deferredCommands = deferredCommands;
    this.audit = audit;
    this.clock = clock;
  }

  /** Normal delivery. A redelivery of an already DEFERRED message is ACK-safe and not re-evaluated. */
  public ProcessingResult process(ProjectionEvent event) {
    boolean alreadyApplied = inbox.find(CONSUMER, event.metadata().messageId())
        .map(message -> "APPLIED".equals(message.status()))
        .orElse(false);
    ProcessingResult result = transactions.inTransaction(() -> processInTransaction(event, null));
    if (!alreadyApplied && result.status() == Status.APPLIED) {
      wakeAfterApplied(event);
    }
    return result;
  }

  /**
   * Explicit recovery path for the original deferred messageId. A durable lease/fencing token
   * prevents two recovery workers from applying the same deferred event concurrently.
   */
  public ProcessingResult replayDeferred(ProjectionEvent event) {
    UUID claimToken = UUID.randomUUID();
    Instant claimedAt = clock.instant();
    boolean claimed = transactions.inTransaction(() -> inbox.claimDeferred(
        CONSUMER,
        event.metadata().messageId(),
        claimToken,
        claimedAt.plus(REPLAY_LEASE),
        claimedAt));
    if (!claimed) {
      return new ProcessingResult(Status.DEFERRED, false);
    }
    try {
      return transactions.inTransaction(() -> processInTransaction(event, claimToken));
    } catch (RuntimeException failure) {
      transactions.inTransaction(() -> {
        inbox.releaseClaim(CONSUMER, event.metadata().messageId(), claimToken, clock.instant());
        return null;
      });
      throw failure;
    }
  }

  private ProcessingResult processInTransaction(ProjectionEvent event, UUID claimToken) {
    validateContract(event);
    ProjectionEventMetadata metadata = event.metadata();
    Instant now = clock.instant();

    Optional<ProcessedMessageRepository.ProcessedMessage> existing =
        inbox.find(CONSUMER, metadata.messageId());
    if (existing.isPresent()) {
      if ("APPLIED".equals(existing.get().status())) {
        return new ProcessingResult(Status.APPLIED, false);
      }
      if (claimToken == null) {
        return new ProcessingResult(Status.DEFERRED, false);
      }
    } else {
      if (claimToken != null) {
        throw new IllegalStateException("claimed deferred message disappeared");
      }
      inbox.insertIfAbsent(new ProcessedMessageRepository.ProcessedMessage(
          CONSUMER,
          metadata.messageId(),
          "DEFERRED",
          metadata.messageType(),
          metadata.messageVersion(),
          "EVENT",
          metadata.producer(),
          metadata.correlationId(),
          metadata.causationId(),
          metadata.operationId(),
          metadata.idempotencyKey(),
          metadata.aggregateId(),
          metadata.aggregateVersion(),
          metadata.actor(),
          retainedInboxPayload(event),
          now,
          metadata.occurredAt(),
          null,
          "PROCESSING",
          now));
    }

    if (isStale(event)) {
      markApplied(metadata.messageId(), claimToken, now);
      return new ProcessingResult(Status.APPLIED, false);
    }

    if (!isCausallyApplicable(event)) {
      keepDeferred(metadata.messageId(), claimToken, "EVENT_PENDING", now);
      return new ProcessingResult(Status.DEFERRED, false);
    }

    boolean changed;
    UUID provisioningAccountId = null;
    if (event instanceof PersonProjectionEvent person) {
      changed = persons.upsertIfNewer(new PersonStatusProjectionRepository.PersonStatusProjection(
          person.personId(), person.status(), metadata.aggregateVersion(), metadata.occurredAt())) == 1;
    } else if (event instanceof AccountProjectionEvent account) {
      var current = accounts.findByAccountId(account.accountId());
      var loginType = "AccountCreated".equals(metadata.messageType())
          ? account.loginType()
          : current.orElseThrow().loginType();
      changed = accounts.upsertIfNewer(new AccountStatusProjectionRepository.AccountStatusProjection(
          account.accountId(),
          account.personId(),
          loginType,
          account.status(),
          metadata.aggregateVersion(),
          metadata.occurredAt())) == 1;
      provisioningAccountId = account.accountId();
    } else if (event instanceof AcademicEligibilityProjectionEvent academic) {
      changed = eligibility.upsertIfNewer(
          new AcademicEligibilityProjectionRepository.AcademicEligibilityProjection(
              academic.eligibilityId(),
              academic.studentAcademicProfileId(),
              academic.systemCode(),
              academic.accountId(),
              academic.eligible(),
              academic.policyVersion(),
              academic.eligibilityVersion(),
              academic.academicProfileVersion(),
              null,
              academic.evaluatedAt(),
              metadata.occurredAt())) == 1;
      provisioningAccountId = academic.accountId();
    } else {
      throw new IllegalArgumentException("unsupported projection event: " + event.getClass());
    }

    if (changed && provisioningAccountId != null) {
      provisioning.evaluate(
          provisioningAccountId,
          new PracticesProvisioningService.Trigger(
              metadata.messageId(),
              metadata.producer(),
              metadata.correlationId(),
              metadata.causationId(),
              metadata.operationId(),
              metadata.idempotencyKey(),
              null,
              null,
              metadata.occurredAt()));
    }
    markApplied(metadata.messageId(), claimToken, now);
    audit.append(new AuthorizationAuditRepository.AuditEntry(
        UUID.randomUUID(),
        metadata.messageId(),
        metadata.producer(),
        eventAggregateType(event),
        metadata.aggregateId(),
        metadata.aggregateVersion(),
        changed ? "PROJECTION_APPLIED" : "PROJECTION_STALE_RESOLVED",
        metadata.actor(),
        null,
        null,
        metadata.correlationId(),
        metadata.causationId(),
        metadata.operationId(),
        metadata.idempotencyKey(),
        "SUCCEEDED",
        null,
        null,
        now));
    return new ProcessingResult(Status.APPLIED, changed);
  }

  private void markApplied(UUID messageId, UUID claimToken, Instant now) {
    int updated = claimToken == null
        ? inbox.markApplied(CONSUMER, messageId, now, now)
        : inbox.markAppliedClaimed(CONSUMER, messageId, claimToken, now, now);
    if (updated != 1) {
      throw new IllegalStateException("deferred replay lost its Inbox fencing token");
    }
  }

  private void keepDeferred(
      UUID messageId, UUID claimToken, String reason, Instant now) {
    int updated = claimToken == null
        ? inbox.updateDeferredReason(CONSUMER, messageId, reason, now)
        : inbox.updateDeferredReasonClaimed(CONSUMER, messageId, claimToken, reason, now);
    if (updated != 1) {
      throw new IllegalStateException("deferred replay lost its Inbox fencing token");
    }
  }

  private void wakeAfterApplied(ProjectionEvent event) {
    UUID aggregateId = event.metadata().aggregateId();
    for (ProcessedMessageRepository.ProcessedMessage deferred :
        inbox.findDeferredByAggregate(CONSUMER, aggregateId, DEFERRED_DRAIN_LIMIT)) {
      ProjectionEvent decoded = deferredEventDecoder.decode(deferred);
      replayDeferred(decoded);
    }
    if (event instanceof AccountProjectionEvent account) {
      deferredCommands.replayForAccount(account.accountId(), "EVENT_PENDING", false);
    }
  }

  private boolean isStale(ProjectionEvent event) {
    ProjectionEventMetadata metadata = event.metadata();
    if (event instanceof PersonProjectionEvent person) {
      return persons.findByPersonId(person.personId())
          .map(current -> metadata.aggregateVersion() <= current.sourceVersion())
          .orElse(false);
    }
    if (event instanceof AccountProjectionEvent account) {
      return accounts.findByAccountId(account.accountId())
          .map(current -> metadata.aggregateVersion() <= current.sourceVersion())
          .orElse(false);
    }
    AcademicEligibilityProjectionEvent academic = (AcademicEligibilityProjectionEvent) event;
    return eligibility.findByStudentProfileAndSystem(
            academic.studentAcademicProfileId(), academic.systemCode())
        .map(current -> academic.eligibilityVersion() <= current.eligibilityVersion())
        .orElse(false);
  }

  private boolean isCausallyApplicable(ProjectionEvent event) {
    String type = event.metadata().messageType();
    if (event instanceof AcademicEligibilityProjectionEvent) {
      return true;
    }
    if (event instanceof PersonProjectionEvent person) {
      boolean exists = persons.findByPersonId(person.personId()).isPresent();
      return "PersonCreated".equals(type) ? !exists : exists;
    }
    AccountProjectionEvent account = (AccountProjectionEvent) event;
    Optional<AccountStatusProjectionRepository.AccountStatusProjection> current =
        accounts.findByAccountId(account.accountId());
    if ("AccountCreated".equals(type)) {
      return current.isEmpty();
    }
    return current.filter(value -> value.personId().equals(account.personId())).isPresent();
  }

  private static void validateContract(ProjectionEvent event) {
    ProjectionEventMetadata metadata = event.metadata();
    if (event instanceof PersonProjectionEvent person) {
      if (!"users".equals(metadata.producer()) || !metadata.aggregateId().equals(person.personId())) {
        throw new IllegalArgumentException("invalid Person event owner or aggregate");
      }
      boolean active = person.status() == ProjectedStatus.ACTIVE;
      if (!("PersonCreated".equals(metadata.messageType()) && active)
          && !("PersonActivated".equals(metadata.messageType()) && active)
          && !("PersonDeactivated".equals(metadata.messageType()) && !active)) {
        throw new IllegalArgumentException("invalid Person event transition");
      }
      return;
    }
    if (event instanceof AccountProjectionEvent account) {
      if (!"authentication".equals(metadata.producer())
          || !metadata.aggregateId().equals(account.accountId())) {
        throw new IllegalArgumentException("invalid Account event owner or aggregate");
      }
      if ("AccountCreated".equals(metadata.messageType())) {
        if (account.loginType() == null || account.status() != ProjectedStatus.INACTIVE) {
          throw new IllegalArgumentException("AccountCreated requires loginType and INACTIVE status");
        }
      } else if ("AccountActivated".equals(metadata.messageType())) {
        if (account.status() != ProjectedStatus.ACTIVE) {
          throw new IllegalArgumentException("AccountActivated requires ACTIVE status");
        }
      } else if ("AccountDeactivated".equals(metadata.messageType())) {
        if (account.status() != ProjectedStatus.INACTIVE) {
          throw new IllegalArgumentException("AccountDeactivated requires INACTIVE status");
        }
      } else {
        throw new IllegalArgumentException("unsupported Account event type");
      }
      return;
    }
    AcademicEligibilityProjectionEvent academic = (AcademicEligibilityProjectionEvent) event;
    if (!"academic".equals(metadata.producer())
        || !"StudentAcademicEligibilityChanged".equals(metadata.messageType())
        || !metadata.aggregateId().equals(academic.eligibilityId())
        || metadata.aggregateVersion() != academic.eligibilityVersion()
        || !SystemCode.PRACTICAS_PROFESIONALES.name().equals(academic.systemCode())) {
      throw new IllegalArgumentException("invalid Academic eligibility event contract");
    }
  }

  private static String retainedInboxPayload(ProjectionEvent event) {
    if (event instanceof PersonProjectionEvent person) {
      return CanonicalJson.write(Map.of(
          "personId", person.personId(),
          "status", person.status()));
    }
    if (event instanceof AccountProjectionEvent account) {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("accountId", account.accountId());
      values.put("personId", account.personId());
      values.put("status", account.status());
      if (account.loginType() != null) {
        values.put("loginType", account.loginType());
      }
      return CanonicalJson.write(values);
    }
    AcademicEligibilityProjectionEvent academic = (AcademicEligibilityProjectionEvent) event;
    return CanonicalJson.write(Map.of(
        "eligibilityId", academic.eligibilityId(),
        "studentAcademicProfileId", academic.studentAcademicProfileId(),
        "accountId", academic.accountId(),
        "systemCode", academic.systemCode(),
        "eligible", academic.eligible(),
        "policyVersion", academic.policyVersion(),
        "eligibilityVersion", academic.eligibilityVersion(),
        "academicProfileVersion", academic.academicProfileVersion(),
        "evaluatedAt", academic.evaluatedAt()));
  }

  private static String eventAggregateType(ProjectionEvent event) {
    return switch (event) {
      case PersonProjectionEvent ignored -> "Person";
      case AccountProjectionEvent ignored -> "Account";
      case AcademicEligibilityProjectionEvent ignored -> "AcademicEligibility";
    };
  }

  public enum Status {
    APPLIED,
    DEFERRED
  }

  public record ProcessingResult(Status status, boolean changed) {}
}

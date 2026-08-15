package com.estoma.authorization.application.projection;

import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.AcademicEligibilityProjectionRepository;
import com.estoma.authorization.application.port.AuthorizationAuditRepository;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.TransactionRunner;
import java.time.Instant;
import java.util.UUID;

public final class ProjectionEventProcessor {

  private static final String CONSUMER = "authorization-events";
  private final TransactionRunner transactions;
  private final ProcessedMessageRepository inbox;
  private final PersonStatusProjectionRepository persons;
  private final AccountStatusProjectionRepository accounts;
  private final AcademicEligibilityProjectionRepository eligibility;
  private final PracticesProvisioningService provisioning;
  private final AuthorizationAuditRepository audit;

  public ProjectionEventProcessor(
      TransactionRunner transactions,
      ProcessedMessageRepository inbox,
      PersonStatusProjectionRepository persons,
      AccountStatusProjectionRepository accounts,
      AcademicEligibilityProjectionRepository eligibility,
      PracticesProvisioningService provisioning,
      AuthorizationAuditRepository audit) {
    this.transactions = transactions;
    this.inbox = inbox;
    this.persons = persons;
    this.accounts = accounts;
    this.eligibility = eligibility;
    this.provisioning = provisioning;
    this.audit = audit;
  }

  public ProcessingResult process(ProjectionEvent event) {
    return transactions.inTransaction(() -> processInTransaction(event));
  }

  private ProcessingResult processInTransaction(ProjectionEvent event) {
    ProjectionEventMetadata metadata = event.metadata();
    ProcessedMessageRepository.ProcessedMessage stored = new ProcessedMessageRepository.ProcessedMessage(
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
        event.payloadJson(),
        metadata.occurredAt(),
        metadata.occurredAt(),
        null,
        event.causallyApplicable() ? "PROCESSING" : "EVENT_PENDING",
        metadata.occurredAt());
    boolean inserted = inbox.insertIfAbsent(stored);
    if (!inserted) {
      var existing = inbox.find(CONSUMER, metadata.messageId());
      if (existing.isPresent() && "APPLIED".equals(existing.get().status())) {
        return new ProcessingResult(Status.APPLIED, false);
      }
    }
    if (!event.causallyApplicable()) {
      return new ProcessingResult(Status.DEFERRED, false);
    }

    boolean changed;
    UUID provisioningAccountId = null;
    if (event instanceof PersonProjectionEvent person) {
      changed = persons.upsertIfNewer(new PersonStatusProjectionRepository.PersonStatusProjection(
          person.personId(), person.status(), metadata.aggregateVersion(), metadata.occurredAt())) == 1;
    } else if (event instanceof AccountProjectionEvent account) {
      changed = accounts.upsertIfNewer(new AccountStatusProjectionRepository.AccountStatusProjection(
          account.accountId(), account.personId(), account.loginType(), account.status(),
          metadata.aggregateVersion(), metadata.occurredAt())) == 1;
      provisioningAccountId = account.accountId();
    } else if (event instanceof AcademicEligibilityProjectionEvent academic) {
      changed = eligibility.upsertIfNewer(new AcademicEligibilityProjectionRepository.AcademicEligibilityProjection(
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
    inbox.markApplied(CONSUMER, metadata.messageId(), Instant.now(), Instant.now());
    audit.append(new AuthorizationAuditRepository.AuditEntry(
        UUID.randomUUID(),
        metadata.messageId(),
        metadata.producer(),
        eventAggregateType(event),
        metadata.aggregateId(),
        metadata.aggregateVersion(),
        "PROJECTION_APPLIED",
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
        metadata.occurredAt()));
    return new ProcessingResult(Status.APPLIED, changed);
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

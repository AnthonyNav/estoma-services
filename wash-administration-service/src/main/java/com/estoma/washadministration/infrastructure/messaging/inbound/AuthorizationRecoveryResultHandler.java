package com.estoma.washadministration.infrastructure.messaging.inbound;

import com.estoma.washadministration.application.port.AuthorizationAccessProjectionRepository;
import com.estoma.washadministration.application.port.AuthorizationRoleProjectionRepository;
import com.estoma.washadministration.application.port.ProcessedMessageRepository;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository.RecoveryJob;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.consumed.AccountAuthorizationSnapshot;
import com.estoma.washadministration.contracts.consumed.AuthorizationAccessSnapshot;
import com.estoma.washadministration.contracts.consumed.AuthorizationRoleSnapshot;
import com.estoma.washadministration.contracts.consumed.AuthorizationSnapshotPage;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuthorizationRecoveryResultHandler {

  private static final String CONSUMER = "wash-administration.authorization-recovery-results";
  private static final String SYSTEM_CODE = "LAVADO_ULTRASONICO";

  private final ProcessedMessageRepository processedMessages;
  private final ProjectionRecoveryJobRepository jobs;
  private final AuthorizationRoleProjectionRepository roles;
  private final AuthorizationAccessProjectionRepository accesses;
  private final EnvelopeJsonCodec codec;

  public AuthorizationRecoveryResultHandler(
      ProcessedMessageRepository processedMessages,
      ProjectionRecoveryJobRepository jobs,
      AuthorizationRoleProjectionRepository roles,
      AuthorizationAccessProjectionRepository accesses,
      EnvelopeJsonCodec codec) {
    this.processedMessages = processedMessages;
    this.jobs = jobs;
    this.roles = roles;
    this.accesses = accesses;
    this.codec = codec;
  }

  @Transactional
  public void handle(MessageEnvelope envelope) {
    Instant now = envelope.occurredAt() == null ? Instant.now() : envelope.occurredAt();
    if (!processedMessages.registerIfAbsent(CONSUMER, envelope.messageId(), envelope.correlationId(), now)) {
      return;
    }
    RecoveryJob job = jobs.findWaitingByQueryMessageIdForUpdate(envelope.causationId()).orElse(null);
    if (job == null) {
      return;
    }
    validateCorrelation(job, envelope);
    AuthorizationRecoveryResultPayload result = codec.convertPayload(envelope, AuthorizationRecoveryResultPayload.class);
    if (result.status() == null) {
      throw new InvalidMessageException("AUTHORIZATION_RESULT_STATUS_REQUIRED");
    }
    roles.lockProjection();
    if ("NOT_FOUND".equals(result.status())) {
      handleNotFound(job, envelope);
      return;
    }
    if ("REJECTED".equals(result.status())
        || "FAILED".equals(result.status())
        || "EXPIRED".equals(result.status())) {
      requireTransition(
          jobs.markFailed(job.recoveryJobId(), envelope.causationId(), "AUTHORIZATION_" + result.status()),
          "authorization recovery terminal failure transition was fenced");
      return;
    }
    if (job.scope() == ProjectionRecoveryJobRepository.Scope.ENTITY) {
      if (!"FOUND".equals(result.status())) {
        throw new InvalidMessageException("AUTHORIZATION_ENTITY_RESULT_STATUS_INVALID");
      }
      AccountAuthorizationSnapshot snapshot = codec.convertNode(result.data(), AccountAuthorizationSnapshot.class);
      if (entitySnapshotConflicts(snapshot)) {
        requireTransition(
            jobs.markFailed(job.recoveryJobId(), envelope.causationId(), "AUTHORIZATION_SNAPSHOT_CONFLICT"),
            "entity recovery conflict transition was fenced");
        jobs.createFull(Instant.now());
        return;
      }
      applySnapshot(snapshot);
      requireTransition(
          jobs.markCompleted(job.recoveryJobId(), envelope.causationId()),
          "entity recovery completion was fenced");
    } else {
      if (!"SUCCEEDED".equals(result.status())) {
        throw new InvalidMessageException("AUTHORIZATION_FULL_RESULT_STATUS_INVALID");
      }
      AuthorizationSnapshotPage page = codec.convertNode(result.data(), AuthorizationSnapshotPage.class);
      if (page.items() == null) {
        throw new InvalidMessageException("AUTHORIZATION_RECOVERY_ITEMS_REQUIRED");
      }
      page.items().forEach(this::applySnapshot);
      boolean changed;
      if (page.hasMore()) {
        if (page.nextCursor() == null || page.nextCursor().isBlank()) {
          throw new InvalidMessageException("AUTHORIZATION_RECOVERY_NEXT_CURSOR_REQUIRED");
        }
        changed = jobs.markPendingAfterPage(job.recoveryJobId(), envelope.causationId(), page.nextCursor());
      } else {
        changed = jobs.markCompleted(job.recoveryJobId(), envelope.causationId());
      }
      requireTransition(changed, "full recovery completion was fenced");
    }
  }

  private void validateCorrelation(RecoveryJob job, MessageEnvelope envelope) {
    String expectedType = job.currentQueryType() + "Result";
    if (!expectedType.equals(envelope.messageType())
        || !Objects.equals(job.currentQueryMessageId(), envelope.causationId())
        || !Objects.equals(job.currentOperationId(), envelope.operationId())
        || !Objects.equals(job.currentCorrelationId(), envelope.correlationId())) {
      throw new InvalidMessageException("AUTHORIZATION_RESULT_CORRELATION_MISMATCH");
    }
  }

  private void handleNotFound(RecoveryJob job, MessageEnvelope envelope) {
    if (job.scope() == ProjectionRecoveryJobRepository.Scope.FULL) {
      requireTransition(
          jobs.markFailed(job.recoveryJobId(), envelope.causationId(), "FULL_RECOVERY_NOT_FOUND"),
          "full not-found recovery failure transition was fenced");
      return;
    }
    java.util.UUID accountId = java.util.UUID.fromString(job.entityKey().substring("account:".length()));
    boolean present = roles.findByAccountId(accountId).isPresent()
        || accesses.findByAccountId(accountId, SYSTEM_CODE).isPresent();
    if (present) {
      requireTransition(
          jobs.markFailed(job.recoveryJobId(), envelope.causationId(), "NOT_FOUND_CONFLICT"),
          "not-found conflict transition was fenced");
      jobs.createFull(Instant.now());
    } else {
      requireTransition(
          jobs.markCompleted(job.recoveryJobId(), envelope.causationId()),
          "not-found recovery completion was fenced");
    }
  }

  private void applySnapshot(AccountAuthorizationSnapshot snapshot) {
    if (snapshot == null || snapshot.accountId() == null) {
      throw new InvalidMessageException("AUTHORIZATION_SNAPSHOT_ACCOUNT_REQUIRED");
    }
    AuthorizationRoleSnapshot role = snapshot.role();
    if (role != null && (role.roleCode() == null || role.roleCode().isBlank() || role.roleVersion() <= 0)) {
      throw new InvalidMessageException("AUTHORIZATION_SNAPSHOT_ROLE_INVALID");
    }
    if (role != null) {
      roles.upsert(snapshot.accountId(), role.roleCode(), role.roleVersion(), Instant.now());
    }
    List<AuthorizationAccessSnapshot> systemAccesses = snapshot.systemAccesses();
    if (systemAccesses == null) {
      throw new InvalidMessageException("AUTHORIZATION_SNAPSHOT_ACCESSES_REQUIRED");
    }
    for (AuthorizationAccessSnapshot access : systemAccesses) {
      if (access == null
          || !SYSTEM_CODE.equals(access.systemCode())
          || access.systemAccessId() == null
          || access.systemId() == null
          || access.status() == null
          || access.accessVersion() <= 0
          || !List.of("ACTIVE", "SUSPENDED", "REVOKED").contains(access.status())) {
        throw new InvalidMessageException("AUTHORIZATION_SNAPSHOT_ACCESS_INVALID");
      }
      accesses.upsert(
          snapshot.accountId(), SYSTEM_CODE, access.status(), access.accessVersion(), Instant.now());
    }
  }

  private boolean entitySnapshotConflicts(AccountAuthorizationSnapshot snapshot) {
    if (snapshot == null || snapshot.accountId() == null || snapshot.systemAccesses() == null) {
      throw new InvalidMessageException("AUTHORIZATION_SNAPSHOT_INVALID");
    }
    boolean localRolePresent = roles.findByAccountId(snapshot.accountId()).isPresent();
    boolean localAccessPresent = accesses.findByAccountId(snapshot.accountId(), SYSTEM_CODE).isPresent();
    boolean snapshotHasAccess = snapshot.systemAccesses().stream()
        .anyMatch(access -> access != null && SYSTEM_CODE.equals(access.systemCode()));
    return (snapshot.role() == null && localRolePresent)
        || (!snapshotHasAccess && localAccessPresent);
  }

  private static void requireTransition(boolean changed, String message) {
    if (!changed) {
      throw new IllegalStateException(message);
    }
  }
}

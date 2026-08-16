package com.estoma.washadministration.infrastructure.messaging.inbound;

import com.estoma.washadministration.application.port.AuthorizationAccessProjectionRepository;
import com.estoma.washadministration.application.port.AuthorizationRoleProjectionRepository;
import com.estoma.washadministration.application.port.ProcessedMessageRepository;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository.RecoveryJob;
import com.estoma.washadministration.contracts.consumed.AccountAuthorizationSnapshot;
import com.estoma.washadministration.contracts.consumed.AuthorizationAccessSnapshot;
import com.estoma.washadministration.contracts.consumed.AuthorizationRoleSnapshot;
import com.estoma.washadministration.contracts.consumed.AuthorizationSnapshotPage;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washadministration.infrastructure.messaging.inbound.AuthorizationRecoveryResultPayload;
import com.estoma.washadministration.contracts.MessageEnvelope;
import java.time.Instant;
import java.util.List;
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
    RecoveryJob job =
        jobs.findWaitingByQueryMessageIdForUpdate(envelope.causationId()).orElse(null);
    if (job == null) {
      return;
    }
    AuthorizationRecoveryResultPayload result =
        codec.convertPayload(envelope, AuthorizationRecoveryResultPayload.class);
    if (result.status() == null) {
      throw new IllegalArgumentException("recovery Result status is required");
    }
    roles.lockProjection();
    if ("NOT_FOUND".equals(result.status())) {
      handleNotFound(job, envelope);
      return;
    }
    if ("REJECTED".equals(result.status())
        || "FAILED".equals(result.status())
        || "EXPIRED".equals(result.status())) {
      jobs.markFailed(job.recoveryJobId(), envelope.causationId(), "AUTHORIZATION_" + result.status());
      return;
    }
    if (job.scope() == ProjectionRecoveryJobRepository.Scope.ENTITY) {
      if (!"FOUND".equals(result.status())) {
        throw new IllegalArgumentException("entity recovery Result status is invalid");
      }
      AccountAuthorizationSnapshot snapshot =
          codec.convertNode(result.data(), AccountAuthorizationSnapshot.class);
      if (entitySnapshotConflicts(snapshot)) {
        if (!jobs.markFailed(
            job.recoveryJobId(), envelope.causationId(), "AUTHORIZATION_SNAPSHOT_CONFLICT")) {
          throw new IllegalStateException("entity recovery conflict transition was fenced");
        }
        return;
      }
      applySnapshot(snapshot);
      if (!jobs.markCompleted(job.recoveryJobId(), envelope.causationId())) {
        throw new IllegalStateException("entity recovery completion was fenced");
      }
    } else {
      if (!"SUCCEEDED".equals(result.status())) {
        throw new IllegalArgumentException("full recovery Result status is invalid");
      }
      AuthorizationSnapshotPage page = codec.convertNode(result.data(), AuthorizationSnapshotPage.class);
      if (page.items() == null) {
        throw new IllegalArgumentException("authorization recovery page items are required");
      }
      page.items().forEach(this::applySnapshot);
      boolean changed;
      if (page.hasMore()) {
        if (page.nextCursor() == null || page.nextCursor().isBlank()) {
          throw new IllegalArgumentException("authorization recovery nextCursor is required");
        }
        changed = jobs.markPendingAfterPage(job.recoveryJobId(), envelope.causationId(), page.nextCursor());
      } else {
        changed = jobs.markCompleted(job.recoveryJobId(), envelope.causationId());
      }
      if (!changed) {
        throw new IllegalStateException("full recovery completion was fenced");
      }
    }
  }

  private void handleNotFound(RecoveryJob job, MessageEnvelope envelope) {
    if (job.scope() == ProjectionRecoveryJobRepository.Scope.FULL) {
      jobs.markFailed(job.recoveryJobId(), envelope.causationId(), "FULL_RECOVERY_NOT_FOUND");
      return;
    }
    java.util.UUID accountId = java.util.UUID.fromString(job.entityKey().substring("account:".length()));
    boolean present = roles.findByAccountId(accountId).isPresent()
        || accesses.findByAccountId(accountId, SYSTEM_CODE).isPresent();
    if (present) {
      jobs.markFailed(job.recoveryJobId(), envelope.causationId(), "NOT_FOUND_CONFLICT");
    } else if (!jobs.markCompleted(job.recoveryJobId(), envelope.causationId())) {
      throw new IllegalStateException("not-found recovery completion was fenced");
    }
  }

  private void applySnapshot(AccountAuthorizationSnapshot snapshot) {
    if (snapshot == null || snapshot.accountId() == null) {
      throw new IllegalArgumentException("authorization snapshot accountId is required");
    }
    AuthorizationRoleSnapshot role = snapshot.role();
    if (role != null && (role.roleCode() == null || role.roleCode().isBlank() || role.roleVersion() <= 0)) {
      throw new IllegalArgumentException("authorization snapshot role is invalid");
    }
    if (role != null) {
      roles.upsert(snapshot.accountId(), role.roleCode(), role.roleVersion(), Instant.now());
    }
    List<AuthorizationAccessSnapshot> systemAccesses = snapshot.systemAccesses();
    if (systemAccesses == null) {
      throw new IllegalArgumentException("authorization snapshot systemAccesses are required");
    }
    for (AuthorizationAccessSnapshot access : systemAccesses) {
      if (access == null
          || !SYSTEM_CODE.equals(access.systemCode())
          || access.status() == null
          || access.accessVersion() <= 0
          || !List.of("ACTIVE", "SUSPENDED", "REVOKED").contains(access.status())) {
        throw new IllegalArgumentException("authorization snapshot access is invalid");
      }
      accesses.upsert(
          snapshot.accountId(), SYSTEM_CODE, access.status(), access.accessVersion(), Instant.now());
    }
  }

  private boolean entitySnapshotConflicts(AccountAuthorizationSnapshot snapshot) {
    if (snapshot == null || snapshot.accountId() == null || snapshot.systemAccesses() == null) {
      throw new IllegalArgumentException("authorization snapshot is invalid");
    }
    boolean localRolePresent = roles.findByAccountId(snapshot.accountId()).isPresent();
    boolean localAccessPresent =
        accesses.findByAccountId(snapshot.accountId(), SYSTEM_CODE).isPresent();
    boolean snapshotHasAccess =
        snapshot.systemAccesses().stream()
            .anyMatch(access -> access != null && SYSTEM_CODE.equals(access.systemCode()));
    return (snapshot.role() == null && localRolePresent)
        || (!snapshotHasAccess && localAccessPresent);
  }
}

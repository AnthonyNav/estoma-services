package com.estoma.washadministration.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washadministration.application.authorization.AuthorizationEvaluator;
import com.estoma.washadministration.application.command.ActorContext;
import com.estoma.washadministration.application.port.AuthorizationAccessProjectionRepository;
import com.estoma.washadministration.application.port.AuthorizationRoleProjectionRepository;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizationEvaluatorTest {

  private static final UUID ACCOUNT_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void administratorWithActiveWashAccessCanExecuteAdministrativeCommand() {
    AuthorizationEvaluator evaluator = evaluator("ADMINISTRADOR_LAVADO", "ACTIVE");

    assertThat(evaluator.isAuthorized(ActorContext.account(ACCOUNT_ID), "CreateServiceCalendar")).isTrue();
  }

  @Test
  void missingOrSuspendedProjectionFailsClosed() {
    assertThat(evaluator("ADMINISTRADOR_LAVADO", "SUSPENDED")
        .isAuthorized(ActorContext.account(ACCOUNT_ID), "CreateServiceCalendar")).isFalse();
    assertThat(evaluator("SUPERVISOR_LAVADO", "ACTIVE")
        .isAuthorized(ActorContext.account(ACCOUNT_ID), "CreateServiceCalendar")).isFalse();
    assertThat(evaluator(null, null)
        .isAuthorized(ActorContext.account(ACCOUNT_ID), "CreateServiceCalendar")).isFalse();
  }

  @Test
  void recoveryForTheAccountOrFullProjectionFailsClosed() {
    ProjectionRecoveryJobRepository recovery = new ProjectionRecoveryJobRepository() {
      @Override
      public Optional<RecoveryJob> claimNextPending(UUID claimToken, Instant leaseUntil) {
        return Optional.empty();
      }

      @Override
      public RecoveryJob createEntity(UUID accountId, Instant now) {
        throw new UnsupportedOperationException();
      }

      @Override
      public RecoveryJob createFull(Instant now) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<RecoveryJob> findWaitingByQueryMessageIdForUpdate(UUID queryMessageId) {
        return Optional.empty();
      }

      @Override
      public boolean markWaiting(
          UUID recoveryJobId,
          UUID claimToken,
          String queryType,
          UUID queryMessageId,
          UUID operationId,
          UUID correlationId,
          int queryAttempt) {
        return false;
      }

      @Override
      public boolean markPendingAfterPage(UUID recoveryJobId, UUID queryMessageId, String cursor) {
        return false;
      }

      @Override
      public boolean markCompleted(UUID recoveryJobId, UUID queryMessageId) {
        return false;
      }

      @Override
      public boolean markFailed(UUID recoveryJobId, UUID queryMessageId, String errorCode) {
        return false;
      }

      @Override
      public void clearAuthorizationProjection() {}

      @Override
      public boolean hasActiveEntityRecovery(UUID accountId) {
        return ACCOUNT_ID.equals(accountId);
      }

      @Override
      public boolean hasActiveFullRecovery() {
        return false;
      }

      @Override
      public boolean hasFailedFullRecovery() {
        return false;
      }
    };

    AuthorizationEvaluator evaluator = evaluator("ADMINISTRADOR_LAVADO", "ACTIVE", recovery);

    assertThat(evaluator.isAuthorized(ActorContext.account(ACCOUNT_ID), "CreateServiceCalendar"))
        .isFalse();
  }

  private static AuthorizationEvaluator evaluator(String role, String access) {
    return evaluator(role, access, null);
  }

  private static AuthorizationEvaluator evaluator(
      String role, String access, ProjectionRecoveryJobRepository recovery) {
    AuthorizationRoleProjectionRepository roles = new AuthorizationRoleProjectionRepository() {
      @Override
      public Optional<RoleProjection> findByAccountId(UUID accountId) {
        return role == null ? Optional.empty()
            : Optional.of(new RoleProjection(accountId, role, 1, NOW));
      }

      @Override
      public int upsert(UUID accountId, String roleCode, int sourceVersion, Instant updatedAt) {
        return 0;
      }
    };
    AuthorizationAccessProjectionRepository accesses = new AuthorizationAccessProjectionRepository() {
      @Override
      public Optional<AccessProjection> findByAccountId(UUID accountId, String systemCode) {
        return access == null ? Optional.empty()
            : Optional.of(new AccessProjection(accountId, systemCode, access, 1, NOW));
      }

      @Override
      public int upsert(UUID accountId, String systemCode, String accessStatus, int sourceVersion, Instant updatedAt) {
        return 0;
      }
    };
    return new AuthorizationEvaluator(roles, accesses, recovery);
  }
}

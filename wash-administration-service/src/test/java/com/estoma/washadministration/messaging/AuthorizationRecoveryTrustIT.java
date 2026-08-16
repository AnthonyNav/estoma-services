package com.estoma.washadministration.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washadministration.application.authorization.AuthorizationEvaluator;
import com.estoma.washadministration.application.command.ActorContext;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository;
import com.estoma.washadministration.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuthorizationRecoveryTrustIT extends AbstractPostgresIT {

  @Autowired private ProjectionRecoveryJobRepository jobs;
  @Autowired private AuthorizationEvaluator authorization;

  @Test
  void completedEntityRecoveryPreventsRepeatedRepairForKnownAbsence() {
    UUID accountId = UUID.randomUUID();
    Instant now = Instant.now();
    jobs.createEntity(accountId, now);

    UUID claimToken = UUID.randomUUID();
    var claimed = jobs.claimNextPending(claimToken, now.plusSeconds(30)).orElseThrow();
    UUID queryMessageId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    assertThat(jobs.markWaiting(
            claimed.recoveryJobId(), claimToken, "GetAccountAuthorizationSnapshot",
            queryMessageId, operationId, correlationId, 1))
        .isTrue();
    assertThat(jobs.markCompleted(claimed.recoveryJobId(), queryMessageId)).isTrue();

    assertThat(jobs.hasCompletedEntityRecovery(accountId)).isTrue();
    assertThat(authorization.needsEntityRecovery(ActorContext.account(accountId))).isFalse();
    assertThat(authorization.isAuthorized(ActorContext.account(accountId), "CreateServiceCalendar"))
        .isFalse();
  }

  @Test
  void laterSuccessfulFullRecoverySupersedesOlderEntityFailure() {
    UUID accountId = UUID.randomUUID();
    Instant now = Instant.now();
    jobs.createEntity(accountId, now);

    UUID claimToken = UUID.randomUUID();
    var claimed = jobs.claimNextPending(claimToken, now.plusSeconds(30)).orElseThrow();
    UUID queryMessageId = UUID.randomUUID();
    assertThat(jobs.markWaiting(
            claimed.recoveryJobId(), claimToken, "GetAccountAuthorizationSnapshot",
            queryMessageId, UUID.randomUUID(), UUID.randomUUID(), 1))
        .isTrue();
    assertThat(jobs.markFailed(claimed.recoveryJobId(), queryMessageId, "SNAPSHOT_CONFLICT"))
        .isTrue();
    assertThat(jobs.hasFailedEntityRecovery(accountId)).isTrue();

    jdbc.sql(
            """
            INSERT INTO projection_recovery_jobs (
              recovery_job_id, projection_type, scope, status,
              created_at, updated_at, completed_at
            ) VALUES (
              :id, 'AUTHORIZATION', 'FULL', 'COMPLETED',
              now() + interval '1 second', now() + interval '1 second', now() + interval '1 second'
            )
            """)
        .param("id", UUID.randomUUID())
        .update();

    assertThat(jobs.hasCompletedFullRecovery()).isTrue();
    assertThat(jobs.hasFailedEntityRecovery(accountId)).isFalse();
    assertThat(jobs.hasCompletedEntityRecovery(accountId)).isFalse();
  }
}

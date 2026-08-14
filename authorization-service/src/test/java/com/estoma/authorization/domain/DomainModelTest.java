package com.estoma.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authorization.domain.exception.DomainRuleViolationException;
import com.estoma.authorization.domain.model.AccountRoleAssignment;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccess;
import com.estoma.authorization.domain.model.SystemAccessReasonCode;
import com.estoma.authorization.domain.model.SystemAccessStatus;
import com.estoma.authorization.domain.model.SystemCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainModelTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void loginTypeCompatibilityFollowsV1Rules() {
    assertThat(RoleCode.ALUMNO.isCompatibleWith(LoginType.MATRICULA)).isTrue();
    assertThat(RoleCode.SUPERADMIN.isCompatibleWith(LoginType.MATRICULA)).isFalse();
    assertThat(RoleCode.SUPERADMIN.isCompatibleWith(LoginType.USERNAME)).isTrue();
    assertThat(RoleCode.SUPERADMIN.isCompatibleWith(null)).isFalse();
  }

  @Test
  void accountRoleStartsAtVersionOneAndKeepsActor() {
    ActorRef actor = ActorRef.system("academic");
    AccountRoleAssignment assignment =
        AccountRoleAssignment.createNew(
            UUID.randomUUID(), UUID.randomUUID(), RoleCode.ALUMNO, actor, NOW);

    assertThat(assignment.version()).isOne();
    assertThat(assignment.assignedBy()).isEqualTo(actor);
  }

  @Test
  void systemAccessLifecycleRequiresReasonsAndIncrementsVersion() {
    ActorRef actor = ActorRef.account(UUID.randomUUID());
    SystemAccess access =
        SystemAccess.createNew(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            SystemCode.LAVADO_ULTRASONICO,
            actor,
            NOW);

    SystemAccess suspended =
        access.suspend(SystemAccessReasonCode.ADMINISTRATIVE, null, actor, NOW.plusSeconds(1));
    SystemAccess restored = suspended.restore(actor, NOW.plusSeconds(2));

    assertThat(suspended.status()).isEqualTo(SystemAccessStatus.SUSPENDED);
    assertThat(suspended.version()).isEqualTo(2);
    assertThat(restored.status()).isEqualTo(SystemAccessStatus.ACTIVE);
    assertThat(restored.version()).isEqualTo(3);
    assertThat(restored.restoredBy()).isEqualTo(actor);
    assertThatThrownBy(() -> access.suspend(SystemAccessReasonCode.OTHER, null, actor, NOW))
        .isInstanceOf(DomainRuleViolationException.class);
    assertThatThrownBy(() -> access.revoke(SystemAccessReasonCode.SECURITY, null, null, NOW))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void revokedAccessCanOnlyBeRestoredByTheApplicationPolicyLater() {
    ActorRef actor = ActorRef.system("authorization");
    SystemAccess access =
        SystemAccess.createNew(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            SystemCode.PRACTICAS_PROFESIONALES,
            actor,
            NOW);

    SystemAccess revoked = access.revoke(SystemAccessReasonCode.SECURITY, null, actor, NOW.plusSeconds(1));

    assertThat(revoked.status()).isEqualTo(SystemAccessStatus.REVOKED);
    assertThat(revoked.reasonCode()).isEqualTo(SystemAccessReasonCode.SECURITY);
  }
}

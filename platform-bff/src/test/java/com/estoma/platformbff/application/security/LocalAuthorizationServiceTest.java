package com.estoma.platformbff.application.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.CurrentActor;
import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LocalAuthorizationServiceTest {
  @Mock CurrentActor actor;
  @Mock ProjectionQueryPort projections;
  @Mock ProjectionReadinessService readiness;

  private final UUID accountId = UUID.randomUUID();
  private LocalAuthorizationService authorization;

  @BeforeEach
  void setUp() {
    authorization = new LocalAuthorizationService(actor, projections, readiness);
    when(actor.accountId()).thenReturn(Optional.of(accountId));
  }

  @Test
  void globalAdminRequiresTheLocallyProjectedSuperadminRole() {
    when(projections.findRoleCode(accountId)).thenReturn(Optional.of("SUPERADMIN"));

    assertThat(authorization.requireSuperAdministrator()).isEqualTo(accountId);

    verify(readiness).requireAuthorizationReady();
  }

  @Test
  void washAdminRequiresCompatibleRoleAndActiveWashAccess() {
    when(projections.findRoleCode(accountId)).thenReturn(Optional.of("ADMINISTRADOR_LAVADO"));
    when(projections.hasActiveSystemAccess(accountId, LocalAuthorizationService.WASH_SYSTEM_CODE))
        .thenReturn(true);

    assertThat(authorization.requireWashAdministrator()).isEqualTo(accountId);

    verify(readiness).requireAuthorizationReady();
  }

  @Test
  void washAdminFailsClosedWhenTheGeneralAccessIsNotActive() {
    when(projections.findRoleCode(accountId)).thenReturn(Optional.of("SUPERADMIN"));
    when(projections.hasActiveSystemAccess(accountId, LocalAuthorizationService.WASH_SYSTEM_CODE))
        .thenReturn(false);

    assertThatThrownBy(() -> authorization.requireWashAdministrator())
        .isInstanceOf(LocalAuthorizationService.AuthorizationDeniedException.class);
  }
}

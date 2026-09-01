package com.estoma.platformbff.application.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.CurrentSecurityPrincipal;
import com.estoma.platformbff.application.security.EffectiveSystemService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CurrentSessionContextServiceTest {
  @Mock CurrentSecurityPrincipal principal;
  @Mock ProjectionReadinessService readiness;
  @Mock ProjectionQueryPort projections;
  @Mock EffectiveSystemService systems;
  @InjectMocks CurrentSessionContextService service;

  @Test
  void composesOnlyTheCurrentPrincipalsLocalProjectionState() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    when(principal.current()).thenReturn(Optional.of(new CurrentSecurityPrincipal.Principal(
        accountId, personId, UUID.randomUUID(), "NORMAL")));
    when(projections.findAccountPresentation(accountId)).thenReturn(Optional.of(
        new ProjectionQueryPort.AccountPresentation(accountId, personId, "MATRICULA", "202257019", "ACTIVE")));
    when(projections.findPersonPresentation(personId)).thenReturn(Optional.of(
        new ProjectionQueryPort.PersonPresentation(personId, "Antonio", "Navarro", null,
            "antonio@example.invalid", "ACTIVE")));
    when(projections.findRoleCode(accountId)).thenReturn(Optional.of("ALUMNO"));
    when(systems.availableSystemCodes(accountId)).thenReturn(List.of("LAVADO_ULTRASONICO"));

    var response = service.current();

    assertThat(response.accountId()).isEqualTo(accountId);
    assertThat(response.personId()).isEqualTo(personId);
    assertThat(response.displayName()).isEqualTo("Antonio Navarro");
    assertThat(response.loginIdentifier()).isEqualTo("202257019");
    assertThat(response.availableSystemCodes()).containsExactly("LAVADO_ULTRASONICO");
    verify(readiness).requireReady("person", "account-presentation", "authorization", "system-catalog");
  }

  @Test
  void failsClosedInsteadOfReturningAContextWithMissingRole() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    when(principal.current()).thenReturn(Optional.of(new CurrentSecurityPrincipal.Principal(
        accountId, personId, UUID.randomUUID(), "NORMAL")));
    when(projections.findAccountPresentation(accountId)).thenReturn(Optional.of(
        new ProjectionQueryPort.AccountPresentation(accountId, personId, "USERNAME", "admin", "ACTIVE")));
    when(projections.findPersonPresentation(personId)).thenReturn(Optional.of(
        new ProjectionQueryPort.PersonPresentation(personId, "Ana", "García", "Ramos",
            "ana@example.invalid", "ACTIVE")));
    when(projections.findRoleCode(accountId)).thenReturn(Optional.empty());

    assertThatThrownBy(service::current).isInstanceOf(ProjectionUnavailableException.class);
  }
}

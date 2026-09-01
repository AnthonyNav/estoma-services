package com.estoma.platformbff.application.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EffectiveSystemServiceTest {
  @Mock ProjectionQueryPort projections;
  @Mock ProjectionReadinessService readiness;
  @InjectMocks EffectiveSystemService service;

  @Test
  void preservesNonAcademicSystemsAndGatesPracticesOnlyWithPublishedEligibility() {
    UUID accountId = UUID.randomUUID();
    when(projections.findActiveSystemCandidates(accountId))
        .thenReturn(List.of("LAVADO_ULTRASONICO", "PRACTICAS_PROFESIONALES"));
    when(projections.hasEligibleAcademicSystem(accountId, "PRACTICAS_PROFESIONALES")).thenReturn(true);

    assertThat(service.availableSystemCodes(accountId))
        .containsExactly("LAVADO_ULTRASONICO", "PRACTICAS_PROFESIONALES");

    verify(readiness).requireReady("authorization", "system-catalog");
    verify(readiness).requireReady("academic");
  }

  @Test
  void removesOnlyPracticesWhenItsEligibilityIsMissingOrIneligible() {
    UUID accountId = UUID.randomUUID();
    when(projections.findActiveSystemCandidates(accountId))
        .thenReturn(List.of("LAVADO_ULTRASONICO", "PRACTICAS_PROFESIONALES"));
    when(projections.hasEligibleAcademicSystem(accountId, "PRACTICAS_PROFESIONALES")).thenReturn(false);

    assertThat(service.availableSystemCodes(accountId)).containsExactly("LAVADO_ULTRASONICO");
  }

  @Test
  void doesNotRequireAcademicReadinessWhenPracticesCannotAffectThisAccount() {
    UUID accountId = UUID.randomUUID();
    when(projections.findActiveSystemCandidates(accountId)).thenReturn(List.of("LAVADO_ULTRASONICO"));

    assertThat(service.availableSystemCodes(accountId)).containsExactly("LAVADO_ULTRASONICO");

    verify(readiness).requireReady("authorization", "system-catalog");
    verifyNoMoreInteractions(readiness);
  }

  @Test
  void rejectsAnySystemThatIsNotEffective() {
    UUID accountId = UUID.randomUUID();
    when(projections.findActiveSystemCandidates(accountId)).thenReturn(List.of());

    assertThatThrownBy(() -> service.requireEffectiveSystem(accountId, "LAVADO_ULTRASONICO"))
        .isInstanceOf(LocalAuthorizationService.AuthorizationDeniedException.class);
  }
}

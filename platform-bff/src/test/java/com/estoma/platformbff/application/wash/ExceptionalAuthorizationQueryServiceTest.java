package com.estoma.platformbff.application.wash;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.estoma.platformbff.application.port.ExceptionalAuthorizationQueryPort;
import com.estoma.platformbff.application.port.ExceptionalAuthorizationQueryPort.Authorization;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExceptionalAuthorizationQueryServiceTest {
  final LocalAuthorizationService access = mock(LocalAuthorizationService.class);
  final ProjectionReadinessService readiness = mock(ProjectionReadinessService.class);
  final ExceptionalAuthorizationQueryPort queries = mock(ExceptionalAuthorizationQueryPort.class);
  final Instant now = Instant.parse("2026-09-19T16:00:00Z");
  final ExceptionalAuthorizationQueryService service = new ExceptionalAuthorizationQueryService(
      access, readiness, queries, Clock.fixed(now, ZoneOffset.UTC));

  @Test void hidesActionForConsumedSupersededExpiredAndUnverifiedLegacyRows() {
    UUID student = UUID.randomUUID(); LocalDate date = LocalDate.of(2026, 9, 19);
    when(queries.list(student, date)).thenReturn(List.of(
        row("AUTHORIZED", true, false, false, null), row("AUTHORIZED", true, false, true, null),
        row("AUTHORIZED", true, true, false, null), row("CANCELLED", true, false, false, null),
        row("AUTHORIZED", true, false, false, now.minusSeconds(1)), row("AUTHORIZED", false, false, false, null)));
    var result = service.list(student, date);
    assertThat(result).extracting(ExceptionalAuthorizationQueryService.Item::status)
        .containsExactly("AVAILABLE", "CONSUMED", "SUPERSEDED", "CANCELLED", "EXPIRED", "UNKNOWN");
    assertThat(result).extracting(ExceptionalAuthorizationQueryService.Item::canCancel)
        .containsExactly(true, false, false, false, false, false);
    verify(readiness).requireReady("appointments");
    verify(access).requireWashSupervisor();
  }
  @Test void operationalDateExpiresAtMexicoCityMidnight() {
    UUID student = UUID.randomUUID(); LocalDate date = LocalDate.of(2026, 9, 18);
    when(queries.list(student, date)).thenReturn(List.of(row("AUTHORIZED", true, false, false, null)));
    assertThat(service.list(student, date).getFirst().status()).isEqualTo("EXPIRED");
  }
  @Test void unauthorizedRequestsCannotReadStudents() {
    when(access.requireWashSupervisor()).thenThrow(new SecurityException());
    assertThatThrownBy(() -> service.search("Alumno")).isInstanceOf(SecurityException.class);
    verifyNoInteractions(queries, readiness);
  }
  @Test void searchRejectsUnboundedEmptySearch() {
    assertThatThrownBy(() -> service.search(" ")).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(queries);
  }
  private Authorization row(String status, boolean known, boolean superseded, boolean consumed, Instant expiry) {
    return new Authorization(UUID.randomUUID(), status, "reason", expiry, known, superseded, consumed);
  }
}

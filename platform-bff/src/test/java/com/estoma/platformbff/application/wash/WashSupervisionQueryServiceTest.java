package com.estoma.platformbff.application.wash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WashSupervisionQueryServiceTest {
  @Mock LocalAuthorizationService authorization;
  @Mock ProjectionReadinessService readiness;
  @Mock ProjectionQueryPort projections;
  @InjectMocks WashSupervisionQueryService service;

  @Test
  void composesQrUsageContextFromTheCanonicalAppointmentWithoutPersistingAnotherIdentifier() {
    UUID appointmentId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    when(authorization.requireWashSupervisor()).thenReturn(UUID.randomUUID());
    when(projections.findPendingReassignments()).thenReturn(List.of(new ProjectionQueryPort.PendingReassignment(
        executionId, "PENDING_REASSIGNMENT", Instant.parse("2026-08-01T10:00:00Z"), 4,
        appointmentId, UUID.randomUUID(), "IMMUNOCOMPROMISED", "SCHEDULED", UUID.randomUUID(),
        "12345", "Endodontics", UUID.randomUUID(), UUID.randomUUID(), "Ada", "Lovelace", null,
        "S001", 7, "ACTIVE", UUID.randomUUID(), "REASSIGNMENT", Instant.parse("2026-08-01T10:05:00Z"),
        UUID.randomUUID(), "CAB-1", "Cabin 1", UUID.randomUUID(), "TANK-1", "Tank 1")));

    var response = service.pendingReassignments();

    assertThat(response.items()).hasSize(1);
    var item = response.items().getFirst();
    assertThat(item.washExecutionId()).isEqualTo(executionId);
    assertThat(item.appointment().courseSection().nrc()).isEqualTo("12345");
    assertThat(item.student().firstName()).isEqualTo("Ada");
    assertThat(item.activeAssignment().tank().code()).isEqualTo("TANK-1");
    assertThat(item.qrUsageContext().appointmentId()).isEqualTo(appointmentId);
    verify(readiness).requireReady("wash-execution", "appointments", "academic", "person",
        "wash-administration");
  }

  @Test
  void degradesTheViewToMissingAssociationsInsteadOfInventingThem() {
    UUID executionId = UUID.randomUUID();
    when(authorization.requireWashSupervisor()).thenReturn(UUID.randomUUID());
    when(projections.findPendingReassignments()).thenReturn(List.of(new ProjectionQueryPort.PendingReassignment(
        executionId, "PENDING_REASSIGNMENT", null, 2,
        null, null, null, null, null, null,
        null, null, null, null, null, null,
        null, null, null, null, null, null,
        null, null, null, null, null, null)));

    var item = service.pendingReassignments().items().getFirst();

    assertThat(item.appointment()).isNull();
    assertThat(item.student()).isNull();
    assertThat(item.activeAssignment()).isNull();
    assertThat(item.qrUsageContext()).isNull();
  }
}

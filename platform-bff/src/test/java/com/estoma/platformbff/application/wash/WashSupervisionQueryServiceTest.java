package com.estoma.platformbff.application.wash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.WashSupervisionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import java.time.Instant;
import java.time.LocalDate;
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
  @Mock WashSupervisionQueryPort projections;
  @InjectMocks WashSupervisionQueryService service;

  @Test
  void composesTheCanonicalSupervisorViewAndUsesNoneForPendingReassignmentQrContext() {
    UUID appointmentId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    when(authorization.requireWashSupervisor()).thenReturn(UUID.randomUUID());
    when(projections.findPendingReassignments()).thenReturn(List.of(new WashSupervisionQueryPort.PendingReassignment(
        executionId, "PENDING_REASSIGNMENT", Instant.parse("2026-08-01T10:00:00Z"), 4,
        appointmentId, "IMMUNOCOMPROMISED", 6, "HIGH_SPEED", "SCHEDULED", slotId,
        LocalDate.parse("2026-08-01"), Instant.parse("2026-08-01T10:00:00Z"),
        Instant.parse("2026-08-01T11:00:00Z"), "America/Mexico_City", UUID.randomUUID(),
        "12345", "Endodontics", UUID.randomUUID(), UUID.randomUUID(), "Ada", "Lovelace", null,
        "S001", 7, "ACTIVE", UUID.randomUUID(), "REASSIGNMENT", Instant.parse("2026-08-01T10:05:00Z"),
        UUID.randomUUID(), "CAB-1", "Cabin 1", UUID.randomUUID(), "TANK-1", "Tank 1",
        1, 2, 3, 4)));

    var response = service.pendingReassignments();

    assertThat(response.items()).hasSize(1);
    var item = response.items().getFirst();
    assertThat(item.washExecutionId()).isEqualTo(executionId);
    assertThat(item.appointment().courseSectionReference().nrc()).isEqualTo("12345");
    assertThat(item.appointment().instrumentCount()).isEqualTo(6);
    assertThat(item.appointment().pieceType()).isEqualTo("HIGH_SPEED");
    assertThat(item.appointment().appointmentTimeSlot().appointmentTimeSlotId()).isEqualTo(slotId);
    assertThat(item.student().displayName()).isEqualTo("Ada Lovelace");
    assertThat(item.student().enrollment()).isEqualTo("S001");
    assertThat(item.activeResourceAssignment().tank().code()).isEqualTo("TANK-1");
    assertThat(item.submittedExitMaterials().witnessTapePortionCount()).isEqualTo(4);
    assertThat(item.qrUsageContext()).isEqualTo("NONE");
    verify(readiness).requireReady("wash-execution", "appointments", "academic", "person",
        "wash-administration");
  }

  @Test
  void degradesMissingAssociationsWithoutInventingThemButQrContextRemainsNone() {
    UUID executionId = UUID.randomUUID();
    when(authorization.requireWashSupervisor()).thenReturn(UUID.randomUUID());
    when(projections.findPendingReassignments()).thenReturn(List.of(empty(executionId)));

    var item = service.pendingReassignments().items().getFirst();

    assertThat(item.appointment()).isNull();
    assertThat(item.student()).isNull();
    assertThat(item.activeResourceAssignment()).isNull();
    assertThat(item.submittedExitMaterials()).isNull();
    assertThat(item.qrUsageContext()).isEqualTo("NONE");
  }

  private static WashSupervisionQueryPort.PendingReassignment empty(UUID executionId) {
    return new WashSupervisionQueryPort.PendingReassignment(
        executionId, "PENDING_REASSIGNMENT", null, 2,
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null,
        null, null, null, null);
  }
}

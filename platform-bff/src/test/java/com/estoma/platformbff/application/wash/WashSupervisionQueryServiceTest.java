package com.estoma.platformbff.application.wash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.WashStudentQueryPort;
import com.estoma.platformbff.application.port.WashSupervisionQueryPort;
import com.estoma.platformbff.application.port.AppointmentQrReadPort;
import com.estoma.platformbff.application.projection.AppointmentQrRecoveryService;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.SupervisorLookupRequest;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.LookupType;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.NextAction;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WashSupervisionQueryServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
  private static final LocalDate SERVICE_DATE = LocalDate.parse("2026-08-27");
  private static final UUID ACCOUNT_ID = UUID.randomUUID();

  @Mock LocalAuthorizationService authorization;
  @Mock ProjectionReadinessService readiness;
  @Mock WashSupervisionQueryPort projections;
  @Mock WashStudentQueryPort studentProjections;
  @Mock AppointmentQrReadPort appointmentQr;
  @Mock AppointmentQrRecoveryService qrRecovery;
  private WashSupervisionQueryService service;

  @BeforeEach
  void setUp() {
    service = new WashSupervisionQueryService(authorization, readiness, projections,
        studentProjections, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void composesHomeForTheServerDerivedServiceDate() {
    when(projections.summarizeHome(SERVICE_DATE)).thenReturn(
        new WashSupervisionQueryPort.HomeSnapshot(12, 5, 4, 1, 0, 3));

    var response = service.home();

    assertThat(response.serviceDate()).isEqualTo(SERVICE_DATE);
    assertThat(response.summary().registeredAppointments()).isEqualTo(12);
    assertThat(response.summary().inProcessAppointments()).isEqualTo(5);
    assertThat(response.summary().completedAppointments()).isEqualTo(4);
    assertThat(response.summary().deniedAppointments()).isEqualTo(1);
    assertThat(response.summary().cancelledAppointments()).isZero();
    assertThat(response.pendingReassignmentsCount()).isEqualTo(3);
    verify(authorization).requireWashSupervisor();
    verify(readiness).requireReady("appointments", "wash-execution");
  }

  @Test
  void manualLookupSelectsTheSingleActiveAppointmentOverANewerTerminalOne() {
    when(studentProjections.findAccountIdByStudentEnrollment("201945678"))
        .thenReturn(Optional.of(ACCOUNT_ID));
    var terminal = candidate("COMPLETED", null, null, false, false);
    var active = candidate("SCHEDULED", null, null, false, false);
    when(projections.findAppointments(ACCOUNT_ID, SERVICE_DATE)).thenReturn(List.of(terminal, active));

    var response = service.lookup(
        new SupervisorLookupRequest(LookupType.STUDENT_ENROLLMENT, null, "201945678"));

    assertThat(response.nextAction()).isEqualTo(NextAction.ENTRY);
    assertThat(response.appointment().appointmentId()).isEqualTo(active.appointmentId());
    assertThat(response.student().studentAccountId()).isEqualTo(ACCOUNT_ID);
    assertThat(response.student().displayName()).isEqualTo("Ana García Reyes");
    assertThat(response.washExecution()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"CANCELLED", "MISSED"})
  void terminalAppointmentWithoutWashExecutionReturnsNone(String appointmentStatus) {
    when(studentProjections.findAccountIdByStudentEnrollment("201945678"))
        .thenReturn(Optional.of(ACCOUNT_ID));
    var terminal = candidate(appointmentStatus, null, null, false, false);
    when(projections.findAppointments(ACCOUNT_ID, SERVICE_DATE)).thenReturn(List.of(terminal));

    var response = service.lookup(
        new SupervisorLookupRequest(LookupType.STUDENT_ENROLLMENT, null, "201945678"));

    assertThat(response.nextAction()).isEqualTo(NextAction.NONE);
    assertThat(response.appointment().appointmentStatus()).isEqualTo(appointmentStatus);
    assertThat(response.washExecution()).isNull();
  }

  @Test
  void lookupMapsEachOperationalExecutionStateToTheDocumentedNextAction() {
    assertAction("PENDING_ENTRY", NextAction.ENTRY_DECISION);
    assertAction("PENDING_REASSIGNMENT", NextAction.REASSIGNMENT);
    assertAction("IN_PROGRESS", NextAction.EXIT_REVIEW);
    assertAction("EXIT_SUBMITTED", NextAction.EXIT_REVIEW);
  }

  @Test
  void pendingReassignmentAfterResourceLossRemainsAnActiveLookup() {
    when(studentProjections.findAccountIdByStudentEnrollment("201945678"))
        .thenReturn(Optional.of(ACCOUNT_ID));
    when(projections.findAppointments(ACCOUNT_ID, SERVICE_DATE)).thenReturn(List.of(
        candidate("IN_PROGRESS", UUID.randomUUID(), "PENDING_REASSIGNMENT", false, false)));

    var response = service.lookup(
        new SupervisorLookupRequest(LookupType.STUDENT_ENROLLMENT, null, "201945678"));

    assertThat(response.nextAction()).isEqualTo(NextAction.REASSIGNMENT);
  }

  @Test
  void exitSubmittedIncludesMaterialsTimestampAndActiveAssignment() {
    when(studentProjections.findAccountIdByStudentEnrollment("201945678"))
        .thenReturn(Optional.of(ACCOUNT_ID));
    var candidate = candidate("IN_PROGRESS", UUID.randomUUID(), "EXIT_SUBMITTED", true, true);
    when(projections.findAppointments(ACCOUNT_ID, SERVICE_DATE)).thenReturn(List.of(candidate));

    var response = service.lookup(
        new SupervisorLookupRequest(LookupType.STUDENT_ENROLLMENT, null, "201945678"));

    assertThat(response.nextAction()).isEqualTo(NextAction.EXIT_REVIEW);
    assertThat(response.washExecution().exitSubmittedAt()).isEqualTo(NOW);
    assertThat(response.washExecution().submittedExitMaterials().packageCount()).isEqualTo(1);
    assertThat(response.activeResourceAssignment().cabin().code()).isEqualTo("C-01");
    assertThat(response.activeResourceAssignment().tank().code()).isEqualTo("T-01");
  }

  @Test
  void contradictoryMultipleActiveAppointmentsFailClosed() {
    when(studentProjections.findAccountIdByStudentEnrollment("201945678"))
        .thenReturn(Optional.of(ACCOUNT_ID));
    when(projections.findAppointments(ACCOUNT_ID, SERVICE_DATE)).thenReturn(List.of(
        candidate("SCHEDULED", null, null, false, false),
        candidate("IN_PROGRESS", UUID.randomUUID(), "IN_PROGRESS", true, false)));

    assertThatThrownBy(() -> service.lookup(
        new SupervisorLookupRequest(LookupType.STUDENT_ENROLLMENT, null, "201945678")))
        .isInstanceOf(ProjectionUnavailableException.class);
  }

  @Test
  void incompleteExitSubmittedContextFailsClosed() {
    when(studentProjections.findAccountIdByStudentEnrollment("201945678"))
        .thenReturn(Optional.of(ACCOUNT_ID));
    when(projections.findAppointments(ACCOUNT_ID, SERVICE_DATE)).thenReturn(List.of(
        candidate("IN_PROGRESS", UUID.randomUUID(), "EXIT_SUBMITTED", true, false)));

    assertThatThrownBy(() -> service.lookup(
        new SupervisorLookupRequest(LookupType.STUDENT_ENROLLMENT, null, "201945678")))
        .isInstanceOf(ProjectionUnavailableException.class);
  }

  @Test
  void terminalAppointmentWithAnActiveExecutionFailsClosed() {
    when(studentProjections.findAccountIdByStudentEnrollment("201945678"))
        .thenReturn(Optional.of(ACCOUNT_ID));
    when(projections.findAppointments(ACCOUNT_ID, SERVICE_DATE)).thenReturn(List.of(
        candidate("CANCELLED", UUID.randomUUID(), "IN_PROGRESS", true, false)));

    assertThatThrownBy(() -> service.lookup(
        new SupervisorLookupRequest(LookupType.STUDENT_ENROLLMENT, null, "201945678")))
        .isInstanceOf(ProjectionUnavailableException.class);
  }

  @Test
  void qrLookupRemainsExplicitlyGated() {
    assertThatThrownBy(() -> service.lookup(new SupervisorLookupRequest(LookupType.QR, "opaque", null)))
        .isInstanceOf(ProjectionUnavailableException.class);
  }

  @Test
  void enabledQrLookupResolvesExactActiveAppointmentLocally() {
    service = new WashSupervisionQueryService(authorization, readiness, projections,
        studentProjections, Clock.fixed(NOW, ZoneOffset.UTC), appointmentQr, qrRecovery, true);
    var candidate = candidate("IN_PROGRESS", UUID.randomUUID(), "PENDING_REASSIGNMENT", false, false);
    when(qrRecovery.isReady(SERVICE_DATE)).thenReturn(true);
    when(appointmentQr.findByExactRepresentation("opaque")).thenReturn(Optional.of(
        new AppointmentQrReadPort.Mapping(candidate.appointmentId(), 1, "opaque")));
    when(projections.findAppointment(candidate.appointmentId(), SERVICE_DATE))
        .thenReturn(Optional.of(candidate));

    var response = service.lookup(new SupervisorLookupRequest(LookupType.QR, "opaque", null));

    assertThat(response.appointment().appointmentId()).isEqualTo(candidate.appointmentId());
    assertThat(response.nextAction()).isEqualTo(NextAction.REASSIGNMENT);
    verify(authorization).requireWashSupervisor();
    verify(readiness).requireReady("person", "academic", "appointments", "wash-execution", "wash-administration");
  }

  @Test
  void enabledQrLookupUsesUniformNotFoundForUnknownRepresentation() {
    service = new WashSupervisionQueryService(authorization, readiness, projections,
        studentProjections, Clock.fixed(NOW, ZoneOffset.UTC), appointmentQr, qrRecovery, true);
    when(qrRecovery.isReady(SERVICE_DATE)).thenReturn(true);
    when(appointmentQr.findByExactRepresentation("unknown")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.lookup(
        new SupervisorLookupRequest(LookupType.QR, "unknown", null)))
        .isInstanceOf(WashSupervisionQueryService.LookupNotFoundException.class);
  }

  @Test
  void enabledQrLookupFailsClosedForTerminalAppointmentView() {
    service = new WashSupervisionQueryService(authorization, readiness, projections,
        studentProjections, Clock.fixed(NOW, ZoneOffset.UTC), appointmentQr, qrRecovery, true);
    var terminal = candidate("COMPLETED", null, null, false, false);
    when(qrRecovery.isReady(SERVICE_DATE)).thenReturn(true);
    when(appointmentQr.findByExactRepresentation("opaque")).thenReturn(Optional.of(
        new AppointmentQrReadPort.Mapping(terminal.appointmentId(), 1, "opaque")));
    when(projections.findAppointment(terminal.appointmentId(), SERVICE_DATE))
        .thenReturn(Optional.of(terminal));

    assertThatThrownBy(() -> service.lookup(
        new SupervisorLookupRequest(LookupType.QR, "opaque", null)))
        .isInstanceOf(ProjectionUnavailableException.class);
  }

  @Test
  void enabledQrLookupFailsClosedForFutureOrMissingAppointmentVersion() {
    service = new WashSupervisionQueryService(authorization, readiness, projections,
        studentProjections, Clock.fixed(NOW, ZoneOffset.UTC), appointmentQr, qrRecovery, true);
    var active = candidate("SCHEDULED", null, null, false, false, null);
    when(qrRecovery.isReady(SERVICE_DATE)).thenReturn(true);
    when(appointmentQr.findByExactRepresentation("opaque")).thenReturn(Optional.of(
        new AppointmentQrReadPort.Mapping(active.appointmentId(), 2, "opaque")));
    when(projections.findAppointment(active.appointmentId(), SERVICE_DATE)).thenReturn(Optional.of(active));

    assertThatThrownBy(() -> service.lookup(
        new SupervisorLookupRequest(LookupType.QR, "opaque", null)))
        .isInstanceOf(ProjectionUnavailableException.class);
  }

  @Test
  void composesTheCanonicalPendingReassignmentView() {
    UUID executionId = UUID.randomUUID();
    when(projections.findPendingReassignments()).thenReturn(List.of(new WashSupervisionQueryPort.PendingReassignment(
        executionId, "PENDING_REASSIGNMENT", NOW, 4, UUID.randomUUID(), "IMMUNOCOMPROMISED", 6,
        "HIGH_SPEED", "SCHEDULED", UUID.randomUUID(), SERVICE_DATE, NOW, NOW.plusSeconds(3600),
        "America/Mexico_City", UUID.randomUUID(), "12345", "Endodontics", ACCOUNT_ID,
        UUID.randomUUID(), "Ada", "Lovelace", null, "S001", 7, "ACTIVE", UUID.randomUUID(),
        "REASSIGNMENT", NOW, UUID.randomUUID(), "CAB-1", "Cabin 1", UUID.randomUUID(),
        "TANK-1", "Tank 1", 1, 2, 3, 4)));

    var item = service.pendingReassignments().items().getFirst();

    assertThat(item.washExecutionId()).isEqualTo(executionId);
    assertThat(item.student().displayName()).isEqualTo("Ada Lovelace");
    assertThat(item.submittedExitMaterials().witnessTapePortionCount()).isEqualTo(4);
    assertThat(item.qrUsageContext()).isEqualTo("NONE");
  }

  private void assertAction(String executionStatus, NextAction expected) {
    org.mockito.Mockito.reset(studentProjections, projections);
    when(studentProjections.findAccountIdByStudentEnrollment("201945678"))
        .thenReturn(Optional.of(ACCOUNT_ID));
    boolean assignment = "IN_PROGRESS".equals(executionStatus) || "EXIT_SUBMITTED".equals(executionStatus);
    boolean materials = "EXIT_SUBMITTED".equals(executionStatus);
    String appointmentStatus = "PENDING_ENTRY".equals(executionStatus)
        || "PENDING_REASSIGNMENT".equals(executionStatus) ? "SCHEDULED" : "IN_PROGRESS";
    when(projections.findAppointments(ACCOUNT_ID, SERVICE_DATE)).thenReturn(List.of(
        candidate(appointmentStatus, UUID.randomUUID(), executionStatus, assignment, materials)));

    assertThat(service.lookup(new SupervisorLookupRequest(
        LookupType.STUDENT_ENROLLMENT, null, "201945678")).nextAction()).isEqualTo(expected);
  }

  private static WashSupervisionQueryPort.LookupCandidate candidate(String appointmentStatus,
      UUID executionId, String executionStatus, boolean assignment, boolean materials) {
    return candidate(appointmentStatus, executionId, executionStatus, assignment, materials, 1L);
  }

  private static WashSupervisionQueryPort.LookupCandidate candidate(String appointmentStatus,
      UUID executionId, String executionStatus, boolean assignment, boolean materials,
      Long appointmentVersion) {
    return new WashSupervisionQueryPort.LookupCandidate(
        ACCOUNT_ID, UUID.randomUUID(), "Ana", "García", "Reyes", "201945678", 7, "ACTIVE",
        UUID.randomUUID(), appointmentVersion, appointmentStatus, "NORMAL", 15, "HIGH_SPEED", UUID.randomUUID(),
        "12345", "Cirugía Bucal", UUID.randomUUID(), NOW, NOW.plusSeconds(3600),
        "America/Mexico_City", executionId, executionStatus, executionId == null ? null : 4L,
        executionId == null ? null : NOW.minusSeconds(60), null,
        materials ? NOW : null, assignment ? UUID.randomUUID() : null, assignment ? "INITIAL" : null,
        assignment ? UUID.randomUUID() : null, assignment ? "C-01" : null,
        assignment ? "Cabina 1" : null, assignment ? UUID.randomUUID() : null,
        assignment ? "T-01" : null, assignment ? "Tanque 1" : null,
        materials ? 1 : null, materials ? 2 : null, materials ? 3 : null, materials ? 4 : null);
  }
}

package com.estoma.platformbff.application.wash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.WashStudentQueryPort;
import com.estoma.platformbff.application.port.AppointmentQrReadPort;
import com.estoma.platformbff.application.projection.AppointmentQrRecoveryService;
import com.estoma.platformbff.application.projection.ProjectionEvent;
import com.estoma.platformbff.application.projection.ProjectionReadModel;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.projection.TypedProjectionProjector;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WashStudentQueryServiceTest {
  private static final UUID ACCOUNT_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

  @Mock LocalAuthorizationService authorization;
  @Mock ProjectionReadinessService readiness;
  @Mock WashStudentQueryPort projections;
  @Mock AppointmentQrReadPort appointmentQr;
  @Mock AppointmentQrRecoveryService qrRecovery;
  WashStudentQueryService service;

  @BeforeEach
  void setUp() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void enabledQrReturnsExactOwnerRepresentationForPendingReassignment() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC), appointmentQr, qrRecovery, true);
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    var appointment = executionAppointment("SCHEDULED", "PENDING_REASSIGNMENT", 4L,
        null, null, false, false);
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(appointment));
    when(qrRecovery.isReady(java.time.LocalDate.of(2026, 8, 27))).thenReturn(true);
    when(appointmentQr.findByAppointmentId(appointment.appointmentId())).thenReturn(Optional.of(
        new AppointmentQrReadPort.Mapping(appointment.appointmentId(), 1, " owner opaque ")));

    var result = service.home().appointment();

    assertThat(result.qrRepresentation()).isEqualTo(" owner opaque ");
    assertThat(result.qrUsageContext()).isEqualTo("NONE");
  }

  @Test
  void enabledQrRequiresReadyCompleteMappingOnlyForActiveAppointment() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC), appointmentQr, qrRecovery, true);
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    var active = scheduledAppointment(UUID.randomUUID(), 2L, NOW, null);
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(active));

    assertThatThrownBy(service::home).isInstanceOf(ProjectionUnavailableException.class);

    when(qrRecovery.isReady(java.time.LocalDate.of(2026, 8, 27))).thenReturn(true);
    assertThatThrownBy(service::home).isInstanceOf(ProjectionUnavailableException.class);
  }

  @Test
  void enabledQrDoesNotRequireRecoveryForTerminalAppointment() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC), appointmentQr, qrRecovery, true);
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(executionAppointment("COMPLETED", "COMPLETED", 6L,
            NOW.minusSeconds(60), NOW.minusSeconds(10), false, true)));

    assertThat(service.home().appointment().qrRepresentation()).isNull();
    verifyNoInteractions(qrRecovery, appointmentQr);
  }

  @Test
  void enabledQrRejectsMappingFromFutureAppointmentVersion() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC), appointmentQr, qrRecovery, true);
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    var active = scheduledAppointment(UUID.randomUUID(), 2L, NOW, null);
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(active));
    when(qrRecovery.isReady(java.time.LocalDate.of(2026, 8, 27))).thenReturn(true);
    when(appointmentQr.findByAppointmentId(active.appointmentId())).thenReturn(Optional.of(
        new AppointmentQrReadPort.Mapping(active.appointmentId(), 3, "opaque")));

    assertThatThrownBy(service::home).isInstanceOf(ProjectionUnavailableException.class);
  }

  @Test
  void enabledQrFailsClosedForIncoherentActiveAppointmentBeforeReturningRepresentation() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC), appointmentQr, qrRecovery, true);
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(executionAppointment("SCHEDULED", "COMPLETED", 6L,
            NOW.minusSeconds(60), NOW.minusSeconds(10), false, true)));

    assertThatThrownBy(service::home).isInstanceOf(ProjectionUnavailableException.class);
    verifyNoInteractions(qrRecovery, appointmentQr);
  }

  @Test
  void enabledQrFailsClosedForInProgressAppointmentWithoutExecution() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC), appointmentQr, qrRecovery, true);
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    var scheduled = scheduledAppointment(UUID.randomUUID(), 2L, NOW, null);
    var inconsistent = new WashStudentQueryPort.Appointment(
        scheduled.appointmentId(), "IN_PROGRESS", scheduled.appointmentVersion(), scheduled.appointmentType(),
        scheduled.instrumentCount(), scheduled.pieceType(), scheduled.courseSectionId(), scheduled.courseNrc(),
        scheduled.courseName(), scheduled.appointmentTimeSlotId(), scheduled.startsAt(), scheduled.endsAt(),
        scheduled.timezone(), scheduled.cancellationDeadlineAt(), scheduled.arrivalToleranceUntil(),
        scheduled.exceptionalAuthorizationId(), scheduled.exceptionalAuthorizationContextKnown(),
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(inconsistent));

    assertThatThrownBy(service::home).isInstanceOf(ProjectionUnavailableException.class);
    verifyNoInteractions(qrRecovery, appointmentQr);
  }

  @Test
  void missingAppointmentVersionFailsAsProjectionUnavailable() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC), appointmentQr, qrRecovery, true);
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(scheduledAppointment(UUID.randomUUID(), null, NOW, null)));

    assertThatThrownBy(service::home).isInstanceOf(ProjectionUnavailableException.class);
    verifyNoInteractions(qrRecovery, appointmentQr);
  }

  @Test
  void composesHomeFromLocalReadModelsAndDerivesStudentExitQrContext() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    UUID appointmentId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(new WashStudentQueryPort.Appointment(appointmentId, "IN_PROGRESS", 3L, "NORMAL",
            10, "HIGH_SPEED", UUID.randomUUID(), "12345", "Cirugía", UUID.randomUUID(),
            NOW, NOW.plusSeconds(3600), "America/Mexico_City", NOW.plusSeconds(300),
            NOW.plusSeconds(600), null, true, executionId,
            "IN_PROGRESS", 3L, NOW.minusSeconds(60), null,
            null, null, null, null, null, null, null, null, null, null,
            UUID.randomUUID(), UUID.randomUUID(), "C-01", "Cabina 1", UUID.randomUUID(), "T-01", "Tanque 1",
            null, null, null, null, null, null, null)));

    var response = service.home();

    assertThat(response.student().fullName()).isEqualTo("Ana García Reyes");
    assertThat(response.appointment().appointmentId()).isEqualTo(appointmentId);
    assertThat(response.appointment().washExecution().washExecutionId()).isEqualTo(executionId);
    assertThat(response.appointment().washExecution().executionVersion()).isEqualTo(3L);
    assertThat(response.appointment().washExecution().activeResourceAssignment().cabinCode()).isEqualTo("C-01");
    assertThat(response.appointment().qrUsageContext()).isEqualTo("STUDENT_EXIT");
    assertThat(response.appointment().studentCancellationAction().name()).isEqualTo("NOT_APPLICABLE");
    verify(readiness).requireReady("person", "academic", "appointments", "wash-execution");
  }

  @Test
  void scheduledAppointmentExposesOwnerVersionExceptionalUsageAndInclusiveCancellationDeadline() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    UUID exceptionalAuthorizationId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(scheduledAppointment(appointmentId, 7L, NOW, exceptionalAuthorizationId)));

    var response = service.home();

    assertThat(response.appointment().appointmentId()).isEqualTo(appointmentId);
    assertThat(response.appointment().appointmentVersion()).isEqualTo(7L);
    assertThat(response.appointment().usesExceptionalAuthorization()).isTrue();
    assertThat(response.appointment().studentCancellationAction().name()).isEqualTo("AVAILABLE");
    assertThat(response.appointment().timeSlot().cancellationDeadlineAt()).isEqualTo(NOW);
  }

  @Test
  void scheduledAppointmentAfterCancellationDeadlineIsDeadlinePassed() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(scheduledAppointment(UUID.randomUUID(), 2L, NOW.minusNanos(1), null)));

    var appointment = service.home().appointment();
    assertThat(appointment.usesExceptionalAuthorization()).isFalse();
    assertThat(appointment.studentCancellationAction().name()).isEqualTo("DEADLINE_PASSED");
  }

  @Test
  void missingCancellationDeadlineFailsClosed() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(scheduledAppointment(UUID.randomUUID(), 2L, null, null)));

    assertThat(service.home().appointment().studentCancellationAction().name()).isEqualTo("NOT_APPLICABLE");
  }

  @Test
  void historicalAppointmentWithoutExceptionalUsageContextFailsClosed() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(scheduledAppointment(UUID.randomUUID(), 2L, NOW.plusSeconds(60), null, false)));

    assertThat(service.home().appointment().studentCancellationAction().name()).isEqualTo("NOT_APPLICABLE");
  }

  @Test
  void appointmentStartedEventProjectsInProgressAndEnablesStudentExitQrInHome() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    UUID appointmentId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    List<ProjectionReadModel> projected = new java.util.ArrayList<>();
    new TypedProjectionProjector(projected::add, null).project(new ProjectionEvent(
        UUID.randomUUID(), "AppointmentStarted", 1, "appointments", NOW, UUID.randomUUID(),
        UUID.randomUUID(), appointmentId, 3, Map.of(
            "appointmentId", appointmentId, "appointmentTimeSlotId", slotId,
            "studentAccountId", ACCOUNT_ID, "appointmentType", "NORMAL")));
    ProjectionReadModel.Appointment started = (ProjectionReadModel.Appointment) projected.getFirst();

    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(new WashStudentQueryPort.Appointment(appointmentId, started.status(), 3L, "NORMAL",
            10, "HIGH_SPEED", UUID.randomUUID(), "12345", "Cirugía", slotId,
            NOW, NOW.plusSeconds(3600), "America/Mexico_City", NOW.plusSeconds(300),
            NOW.plusSeconds(600), null, true, UUID.randomUUID(),
            "IN_PROGRESS", 3L, NOW.minusSeconds(60), null,
            null, null, null, null, null, null, null, null, null, null,
            UUID.randomUUID(), UUID.randomUUID(), "C-01", "Cabina 1", UUID.randomUUID(), "T-01", "Tanque 1",
            null, null, null, null, null, null, null)));

    var response = service.home();

    assertThat(response.appointment().appointmentStatus()).isEqualTo("IN_PROGRESS");
    assertThat(response.appointment().qrUsageContext()).isEqualTo("STUDENT_EXIT");
  }

  @Test
  void exitSubmittedExposesSubmittedMaterialsAndServerVersion() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(executionAppointment("IN_PROGRESS", "EXIT_SUBMITTED", 5L,
            NOW.minusSeconds(30), null, true, false)));

    var execution = service.home().appointment().washExecution();

    assertThat(execution.executionVersion()).isEqualTo(5L);
    assertThat(execution.exitSubmittedAt()).isEqualTo(NOW.minusSeconds(30));
    assertThat(execution.submittedExitMaterials().packageCount()).isEqualTo(1);
    assertThat(execution.finalExitMaterials()).isNull();
    assertThat(execution.activeResourceAssignment()).isNotNull();
    assertThat(service.home().appointment().qrUsageContext()).isEqualTo("SUPERVISOR_EXIT_REVIEW");
  }

  @Test
  void completedExposesOfficialMaterialsAndOnlyHistoricalAssignmentDuringAppointmentConvergence() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(executionAppointment("IN_PROGRESS", "COMPLETED", 6L,
            NOW.minusSeconds(60), NOW.minusSeconds(10), false, true)));

    var response = service.home();
    var execution = response.appointment().washExecution();

    assertThat(response.appointment().appointmentStatus()).isEqualTo("IN_PROGRESS");
    assertThat(execution.completedAt()).isEqualTo(NOW.minusSeconds(10));
    assertThat(execution.finalExitMaterials().packageCount()).isEqualTo(5);
    assertThat(execution.activeResourceAssignment()).isNull();
    assertThat(execution.lastResourceAssignment().cabinCode()).isEqualTo("C-01");
    assertThat(response.appointment().qrUsageContext()).isEqualTo("NONE");
  }

  @Test
  void completedWithActiveAssignmentFailsClosed() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(executionAppointment("COMPLETED", "COMPLETED", 6L,
            NOW.minusSeconds(60), NOW.minusSeconds(10), true, true)));

    assertThatThrownBy(service::home).isInstanceOf(ProjectionUnavailableException.class);
  }

  @Test
  void formContextContainsOnlyLocallyProjectedActiveEnrollments() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    UUID section = UUID.randomUUID();
    when(projections.findAvailableCourseSections(ACCOUNT_ID)).thenReturn(List.of(
        new WashStudentQueryPort.CourseSection(section, "12345", "Cirugía")));

    var response = service.formContext();

    assertThat(response.student().fullName()).isEqualTo("Ana García Reyes");
    assertThat(response.student().studentEnrollment()).isEqualTo("201945678");
    assertThat(response.availableCourseSections()).extracting(value -> value.courseSectionId())
        .containsExactly(section);
    verify(readiness).requireReady("person", "academic");
  }

  private static WashStudentQueryPort.Appointment scheduledAppointment(UUID appointmentId, Long version,
      Instant cancellationDeadlineAt, UUID exceptionalAuthorizationId) {
    return scheduledAppointment(appointmentId, version, cancellationDeadlineAt, exceptionalAuthorizationId, true);
  }

  private static WashStudentQueryPort.Appointment scheduledAppointment(UUID appointmentId, Long version,
      Instant cancellationDeadlineAt, UUID exceptionalAuthorizationId, boolean exceptionalContextKnown) {
    return new WashStudentQueryPort.Appointment(appointmentId, "SCHEDULED", version, "NORMAL",
        10, "HIGH_SPEED", UUID.randomUUID(), "12345", "Cirugía", UUID.randomUUID(),
        NOW, NOW.plusSeconds(3600), "America/Mexico_City", cancellationDeadlineAt,
        NOW.plusSeconds(600), exceptionalAuthorizationId, exceptionalContextKnown,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static WashStudentQueryPort.Appointment executionAppointment(String appointmentStatus,
      String executionStatus, long version, Instant exitSubmittedAt, Instant completedAt,
      boolean active, boolean last) {
    UUID executionId = UUID.randomUUID();
    return new WashStudentQueryPort.Appointment(UUID.randomUUID(), appointmentStatus, 7L, "NORMAL",
        10, "HIGH_SPEED", UUID.randomUUID(), "12345", "Cirugía", UUID.randomUUID(),
        NOW, NOW.plusSeconds(3600), "America/Mexico_City", NOW.plusSeconds(300),
        NOW.plusSeconds(600), null, true, executionId, executionStatus, version, NOW.minusSeconds(120), null,
        exitSubmittedAt, exitSubmittedAt == null ? null : 1, exitSubmittedAt == null ? null : 2,
        exitSubmittedAt == null ? null : 3, exitSubmittedAt == null ? null : 4,
        completedAt, completedAt == null ? null : 5, completedAt == null ? null : 6,
        completedAt == null ? null : 7, completedAt == null ? null : 8,
        active ? UUID.randomUUID() : null, active ? UUID.randomUUID() : null,
        active ? "C-01" : null, active ? "Cabina 1" : null, active ? UUID.randomUUID() : null,
        active ? "T-01" : null, active ? "Tanque 1" : null,
        last ? UUID.randomUUID() : null, last ? UUID.randomUUID() : null,
        last ? "C-01" : null, last ? "Cabina 1" : null, last ? UUID.randomUUID() : null,
        last ? "T-01" : null, last ? "Tanque 1" : null);
  }

  private static WashStudentQueryPort.Student student() {
    return new WashStudentQueryPort.Student("Ana", "García", "Reyes", "201945678", 7);
  }
}

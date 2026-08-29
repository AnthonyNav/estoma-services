package com.estoma.platformbff.application.wash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.WashStudentQueryPort;
import com.estoma.platformbff.application.projection.ProjectionEvent;
import com.estoma.platformbff.application.projection.ProjectionReadModel;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.projection.TypedProjectionProjector;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.wash.WashHttpRequests.EntryLookupRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WashStudentQueryServiceTest {
  private static final UUID ACCOUNT_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

  @Mock LocalAuthorizationService authorization;
  @Mock ProjectionReadinessService readiness;
  @Mock WashStudentQueryPort projections;
  @InjectMocks WashStudentQueryService service;

  @Test
  void composesHomeFromLocalReadModelsAndDerivesStudentExitQrContext() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    when(authorization.requireStudent()).thenReturn(ACCOUNT_ID);
    when(projections.findStudent(ACCOUNT_ID)).thenReturn(Optional.of(student()));
    UUID appointmentId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    when(projections.findPrimaryAppointment(ACCOUNT_ID, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(new WashStudentQueryPort.Appointment(appointmentId, "IN_PROGRESS", "NORMAL",
            10, "HIGH_SPEED", UUID.randomUUID(), "12345", "Cirugía", UUID.randomUUID(),
            NOW, NOW.plusSeconds(3600), "America/Mexico_City", NOW.plusSeconds(600), executionId,
            "IN_PROGRESS", 3L, NOW.minusSeconds(60), null, UUID.randomUUID(), UUID.randomUUID(),
            "C-01", "Cabina 1", UUID.randomUUID(), "T-01", "Tanque 1")));

    var response = service.home();

    assertThat(response.student().fullName()).isEqualTo("Ana García Reyes");
    assertThat(response.appointment().appointmentId()).isEqualTo(appointmentId);
    assertThat(response.appointment().washExecution().washExecutionId()).isEqualTo(executionId);
    assertThat(response.appointment().washExecution().version()).isEqualTo(3L);
    assertThat(response.appointment().washExecution().activeResourceAssignment().cabinCode()).isEqualTo("C-01");
    assertThat(response.appointment().qrUsageContext()).isEqualTo("STUDENT_EXIT");
    verify(readiness).requireReady("person", "academic", "appointments", "wash-execution");
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
        .thenReturn(Optional.of(new WashStudentQueryPort.Appointment(appointmentId, started.status(), "NORMAL",
            10, "HIGH_SPEED", UUID.randomUUID(), "12345", "Cirugía", slotId,
            NOW, NOW.plusSeconds(3600), "America/Mexico_City", NOW.plusSeconds(600), UUID.randomUUID(),
            "IN_PROGRESS", 3L, NOW.minusSeconds(60), null, UUID.randomUUID(), UUID.randomUUID(),
            "C-01", "Cabina 1", UUID.randomUUID(), "T-01", "Tanque 1")));

    var response = service.home();

    assertThat(response.appointment().appointmentStatus()).isEqualTo("IN_PROGRESS");
    assertThat(response.appointment().qrUsageContext()).isEqualTo("STUDENT_EXIT");
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

  @Test
  void supervisorManualLookupComposesTheSameLocalAppointmentReadModel() {
    service = new WashStudentQueryService(authorization, readiness, projections,
        Clock.fixed(NOW, ZoneOffset.UTC));
    UUID executionId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(authorization.requireWashSupervisor()).thenReturn(UUID.randomUUID());
    when(projections.findAccountIdByStudentEnrollment("201945678")).thenReturn(Optional.of(accountId));
    when(projections.findStudent(accountId)).thenReturn(Optional.of(student()));
    when(projections.findPrimaryAppointment(accountId, java.time.LocalDate.of(2026, 8, 27)))
        .thenReturn(Optional.of(appointment(executionId)));

    var response = service.entryLookup(new EntryLookupRequest("STUDENT_ENROLLMENT", null, "201945678"));

    assertThat(response.student().studentEnrollment()).isEqualTo("201945678");
    assertThat(response.washExecution().washExecutionId()).isEqualTo(executionId);
    assertThat(response.appointment().washExecution().version()).isEqualTo(3L);
    verify(readiness).requireReady("person", "academic", "appointments", "wash-execution", "wash-administration");
  }

  @Test
  void supervisorQrLookupIsExplicitlyGatedUntilItsProjectionExists() {
    assertThat(org.assertj.core.api.Assertions.catchThrowable(
        () -> service.entryLookup(new EntryLookupRequest("QR", "opaque", null))))
        .isInstanceOf(ProjectionUnavailableException.class);
  }

  private static WashStudentQueryPort.Appointment appointment(UUID executionId) {
    return new WashStudentQueryPort.Appointment(UUID.randomUUID(), "IN_PROGRESS", "NORMAL",
        10, "HIGH_SPEED", UUID.randomUUID(), "12345", "Cirugía", UUID.randomUUID(),
        NOW, NOW.plusSeconds(3600), "America/Mexico_City", NOW.plusSeconds(600), executionId,
        "IN_PROGRESS", 3L, NOW.minusSeconds(60), null, UUID.randomUUID(), UUID.randomUUID(),
        "C-01", "Cabina 1", UUID.randomUUID(), "T-01", "Tanque 1");
  }

  private static WashStudentQueryPort.Student student() {
    return new WashStudentQueryPort.Student("Ana", "García", "Reyes", "201945678", 7);
  }
}

package com.estoma.appointments.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.in.CancelExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.in.GrantExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.ExceptionalAppointmentAuthorizationRepository;
import com.estoma.appointments.application.usecase.CancelExceptionalAppointmentAuthorizationHandler;
import com.estoma.appointments.application.usecase.GrantExceptionalAppointmentAuthorizationHandler;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import com.estoma.appointments.domain.model.AppointmentType;
import com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization;
import com.estoma.appointments.domain.model.PieceType;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ExceptionalAuthorizationAvailabilityIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-08-24T13:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

  @Autowired GrantExceptionalAppointmentAuthorizationHandler grant;
  @Autowired CancelExceptionalAppointmentAuthorizationHandler cancel;
  @Autowired ExceptionalAppointmentAuthorizationRepository authorizations;
  @Autowired AppointmentRepository appointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired AcademicProjectionRepository academic;
  @Autowired AuthorizationProjectionRepository access;
  @Autowired JdbcClient jdbc;

  private UUID supervisorId;
  private UUID studentId;

  @BeforeEach
  void cleanAndSeedActors() {
    jdbc.sql("""
        TRUNCATE TABLE appointments, appointment_time_slots, exceptional_appointment_authorizations,
          academic_period_projection, student_academic_profile_projection, course_section_projection,
          student_course_section_projection, account_role_projection, wash_system_access_projection,
          command_idempotency_records, outbox_messages CASCADE
        """).update();
    supervisorId = UUID.randomUUID();
    studentId = UUID.randomUUID();
    academic.upsertStudentProfile(new AcademicProjectionRepository.StudentAcademicProfileProjection(
        UUID.randomUUID(), studentId, 5, "ACTIVE", 1, NOW, true));
    access.upsertAccountRole(new AuthorizationProjectionRepository.AccountRoleProjection(
        supervisorId, UUID.randomUUID(), "SUPERVISOR_LAVADO", 1, NOW, true));
    access.upsertSystemAccess(new AuthorizationProjectionRepository.SystemAccessProjection(
        UUID.randomUUID(), supervisorId, UUID.randomUUID(), "LAVADO_ULTRASONICO", "ACTIVE", 1, NOW, true));
  }

  @Test
  void cancelledAuthorizationIsRetainedAndDoesNotBlockANewSupervisorDecision() {
    UUID firstId = UUID.randomUUID();
    assertThat(grant.grant(grantCommand(firstId, TODAY, NOW.minusSeconds(1))).status().name())
        .isEqualTo("APPLIED");
    assertThat(authorizations.findById(firstId).orElseThrow().expiresAt()).isNull();

    assertThat(cancel.cancel(cancelCommand(firstId)).status().name()).isEqualTo("APPLIED");

    UUID secondId = UUID.randomUUID();
    assertThat(grant.grant(grantCommand(secondId, TODAY, NOW.plusSeconds(3_600))).status().name())
        .isEqualTo("APPLIED");
    assertThat(authorizations.findByStudentAndDate(studentId, TODAY))
        .extracting(ExceptionalAppointmentAuthorization::exceptionalAuthorizationId)
        .containsExactlyInAnyOrder(firstId, secondId);
    assertThat(authorizations.findById(firstId).orElseThrow().isCancelled()).isTrue();
    assertThat(authorizations.findById(secondId).orElseThrow().isCancelled()).isFalse();
  }

  @Test
  void consumedAuthorizationDoesNotBlockANewSupervisorDecision() {
    UUID firstId = UUID.randomUUID();
    authorizations.save(ExceptionalAppointmentAuthorization.grant(firstId, studentId, TODAY,
        supervisorId, "first decision", NOW, null));
    UUID slotId = UUID.randomUUID();
    slots.save(AppointmentTimeSlot.create(slotId, UUID.randomUUID(), UUID.randomUUID(), TODAY,
        Instant.parse("2026-08-24T16:00:00Z"), Instant.parse("2026-08-24T17:00:00Z"),
        ZoneId.of("America/Mexico_City"), Instant.parse("2026-08-24T15:50:00Z"),
        Instant.parse("2026-08-24T15:40:00Z"), Instant.parse("2026-08-24T17:10:00Z")));
    appointments.save(Appointment.scheduled(UUID.randomUUID(), slotId, studentId, UUID.randomUUID(),
        AppointmentType.NORMAL, 1, PieceType.HIGH_SPEED, firstId, NOW));

    UUID secondId = UUID.randomUUID();
    var result = grant.grant(grantCommand(secondId, TODAY, null));

    assertThat(result.status().name()).isEqualTo("APPLIED");
    assertThat(authorizations.findByStudentAndDate(studentId, TODAY)).hasSize(2);
  }

  @Test
  void concurrentSupervisorGrantsLeaveOnlyOneAvailableAuthorization() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    Future<String> first = pool.submit(() -> concurrentGrant(ready, go));
    Future<String> second = pool.submit(() -> concurrentGrant(ready, go));

    ready.await();
    go.countDown();

    assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("APPLIED", "REJECTED");
    assertThat(authorizations.findByStudentAndDate(studentId, TODAY)).hasSize(1);
    pool.shutdownNow();
  }

  @Test
  void concludedDateRejectsGrantAndCancelInTheOperationalTimezone() {
    LocalDate concludedDate = TODAY.minusDays(1);
    assertThat(grant.grant(grantCommand(UUID.randomUUID(), concludedDate, null)).code())
        .isEqualTo("AUTHORIZATION_DATE_CONCLUDED");

    UUID authorizationId = UUID.randomUUID();
    authorizations.save(ExceptionalAppointmentAuthorization.grant(authorizationId, studentId,
        concludedDate, supervisorId, "historical decision", NOW.minusSeconds(86_400), null));
    assertThat(cancel.cancel(cancelCommand(authorizationId)).code())
        .isEqualTo("AUTHORIZATION_DATE_CONCLUDED");
  }

  @Test
  void aRepeatedCancellationWithANewIdempotencyKeyIsRejected() {
    UUID authorizationId = UUID.randomUUID();
    authorizations.save(ExceptionalAppointmentAuthorization.grant(authorizationId, studentId, TODAY,
        supervisorId, "decision", NOW, null));
    assertThat(cancel.cancel(cancelCommand(authorizationId)).status().name()).isEqualTo("APPLIED");
    assertThat(cancel.cancel(cancelCommand(authorizationId)).code())
        .isEqualTo("AUTHORIZATION_ALREADY_CANCELLED");
  }

  private GrantExceptionalAppointmentAuthorizationUseCase.Command grantCommand(UUID authorizationId,
      LocalDate serviceDate, Instant ignoredExpiresAt) {
    return new GrantExceptionalAppointmentAuthorizationUseCase.Command(
        CommandMetadata.direct(ActorContext.account(supervisorId), NOW), authorizationId, studentId,
        serviceDate, "supervisor decision", ignoredExpiresAt, NOW);
  }

  private CancelExceptionalAppointmentAuthorizationUseCase.Command cancelCommand(UUID authorizationId) {
    return new CancelExceptionalAppointmentAuthorizationUseCase.Command(
        CommandMetadata.direct(ActorContext.account(supervisorId), NOW), authorizationId,
        "no longer needed", NOW);
  }

  private String concurrentGrant(CountDownLatch ready, CountDownLatch go) throws Exception {
    ready.countDown();
    go.await();
    return grant.grant(grantCommand(UUID.randomUUID(), TODAY, null)).status().name();
  }
}

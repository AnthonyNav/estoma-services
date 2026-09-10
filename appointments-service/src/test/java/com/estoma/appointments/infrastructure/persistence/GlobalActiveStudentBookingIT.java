package com.estoma.appointments.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.estoma.appointments.application.model.*;
import com.estoma.appointments.application.port.in.*;
import com.estoma.appointments.application.port.out.*;
import com.estoma.appointments.application.usecase.*;
import com.estoma.appointments.domain.model.*;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class GlobalActiveStudentBookingIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-09-10T14:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);
  @MockitoSpyBean JdbcAppointmentRepository appointments;
  @Autowired AppointmentTimeSlotRepository slots;
  @Autowired AppointmentCapacityProjectionRepository capacities;
  @Autowired AcademicProjectionRepository academic;
  @Autowired AuthorizationProjectionRepository authorization;
  @Autowired ScheduleAppointmentHandler schedule;
  @Autowired GetAppointmentAvailabilityHandler availability;
  @Autowired JdbcClient jdbc;
  private UUID student, course;

  @BeforeEach void seed() {
    jdbc.sql("TRUNCATE TABLE appointments,appointment_time_slots,appointment_capacity_projections,"
        + "exceptional_appointment_authorizations,academic_period_projection,student_academic_profile_projection,"
        + "course_section_projection,student_course_section_projection,account_role_projection,"
        + "wash_system_access_projection,outbox_messages,command_idempotency_records CASCADE").update();
    student=UUID.randomUUID();course=UUID.randomUUID();UUID period=UUID.randomUUID(),profile=UUID.randomUUID();
    academic.upsertAcademicPeriod(new AcademicProjectionRepository.AcademicPeriodProjection(period,"DEMO","ACTIVE",1,NOW,true));
    academic.upsertStudentProfile(new AcademicProjectionRepository.StudentAcademicProfileProjection(profile,student,8,"ACTIVE",1,NOW,true));
    academic.upsertCourseSection(new AcademicProjectionRepository.CourseSectionProjection(course,period,"ACTIVE",1,NOW,true));
    academic.upsertStudentCourse(new AcademicProjectionRepository.StudentCourseSectionProjection(profile,student,course,period,NOW.minusSeconds(172800),null,1,NOW,true));
    authorization.upsertAccountRole(new AuthorizationProjectionRepository.AccountRoleProjection(student,UUID.randomUUID(),"ALUMNO",1,NOW,true));
    authorization.upsertSystemAccess(new AuthorizationProjectionRepository.SystemAccessProjection(UUID.randomUUID(),student,UUID.randomUUID(),"LAVADO_ULTRASONICO","ACTIVE",1,NOW,true));
  }

  @Test void previousDayActiveAppointmentBlocksAvailabilityAndProducesDurableRejection() {
    var old=slot(NOW.minusSeconds(86400));
    appointments.save(appointment(old));
    var target=slot(NOW.plusSeconds(3600));
    var snapshot=availability.get(request(),NOW);
    assertThat(snapshot.canSchedule()).isFalse();
    assertThat(snapshot.blockingReasons()).contains("ACTIVE_APPOINTMENT_EXISTS");
    assertThat(snapshot.dailyPenaltyPoints()).isZero();
    var command=command(target);
    var result=schedule.schedule(command);
    assertThat(result.status()).isEqualTo(ApplicationResult.Status.REJECTED);
    assertThat(result.code()).isEqualTo("ACTIVE_APPOINTMENT_EXISTS");
    assertThat(schedule.schedule(command)).isEqualTo(result);
    assertThat(count("appointments")).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM command_idempotency_records WHERE status='COMPLETED'").query(Integer.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='ScheduleAppointmentRejected'").query(Integer.class).single()).isEqualTo(1);
  }

  @Test void completedPriorDayDoesNotBlockTodaysReservation() {
    var old=appointment(slot(NOW.minusSeconds(86400))).start(NOW.minusSeconds(86390))
        .complete(UUID.randomUUID(),NOW.minusSeconds(86380));
    appointments.save(old);
    var target=slot(NOW.plusSeconds(3600));
    assertThat(availability.get(request(),NOW).canSchedule()).isTrue();
    assertThat(schedule.schedule(command(target)).status()).isEqualTo(ApplicationResult.Status.APPLIED);
  }

  @Test void concurrentReservationsOnDifferentSlotsCommitOneSuccessAndOneRejection() throws Exception {
    var first=command(slot(NOW.plusSeconds(3600)));
    var second=command(slot(NOW.plusSeconds(7200)));
    var bothAtInsert=new CountDownLatch(2);
    doAnswer(invocation -> {
      bothAtInsert.countDown();
      if (!bothAtInsert.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("both commands must pass the initial active check");
      return invocation.callRealMethod();
    }).when(appointments).saveScheduledUnlessStudentActive(any());
    try (var pool=Executors.newFixedThreadPool(2)) {
      var one=pool.submit(()->schedule.schedule(first));
      var two=pool.submit(()->schedule.schedule(second));
      var results=List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
      assertThat(results).extracting(ApplicationResult::status)
          .containsExactlyInAnyOrder(ApplicationResult.Status.APPLIED,ApplicationResult.Status.REJECTED);
      assertThat(results.stream().filter(r->r.status()==ApplicationResult.Status.REJECTED).findFirst().orElseThrow().code())
          .isEqualTo("ACTIVE_APPOINTMENT_EXISTS");
    }
    assertThat(count("appointments")).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM command_idempotency_records WHERE status='COMPLETED'").query(Integer.class).single()).isEqualTo(2);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type IN ('ScheduleAppointmentSucceeded','ScheduleAppointmentRejected')").query(Integer.class).single()).isEqualTo(2);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AppointmentScheduled'").query(Integer.class).single()).isEqualTo(1);
  }

  private GetAppointmentAvailabilityUseCase.Request request() {
    return new GetAppointmentAvailabilityUseCase.Request(student,TODAY,AppointmentType.NORMAL,1,PieceType.HIGH_SPEED,course);
  }
  private ScheduleAppointmentUseCase.Command command(AppointmentTimeSlot slot) {
    var metadata=new CommandMetadata(UUID.randomUUID(),UUID.randomUUID().toString(),UUID.randomUUID(),null,
        new ActorContext("ACCOUNT",student,null),NOW,UUID.randomUUID());
    return new ScheduleAppointmentUseCase.Command(metadata,UUID.randomUUID(),student,slot.appointmentTimeSlotId(),course,
        AppointmentType.NORMAL,1,PieceType.HIGH_SPEED,null,true,NOW);
  }
  private Appointment appointment(AppointmentTimeSlot slot) {
    return Appointment.scheduled(UUID.randomUUID(),slot.appointmentTimeSlotId(),student,course,
        AppointmentType.NORMAL,1,PieceType.HIGH_SPEED,null,slot.startsAt().minusSeconds(1800));
  }
  private AppointmentTimeSlot slot(Instant start) {
    var slot=AppointmentTimeSlot.create(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),LocalDate.ofInstant(start,ZoneOffset.UTC),
        start,start.plusSeconds(1800),ZoneOffset.UTC,start.minusSeconds(600),start.minusSeconds(1200),start.plusSeconds(600));
    slots.save(slot);capacities.save(new AppointmentCapacityProjectionRepository.CapacityProjection(slot.appointmentTimeSlotId(),4,0,0,1,NOW));
    return slot;
  }
  private int count(String table) { return jdbc.sql("SELECT count(*) FROM "+table).query(Integer.class).single(); }
}

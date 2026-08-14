package com.estoma.academic;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.academic.application.AcademicCommandDispatcher;
import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "estoma.messaging.listeners-enabled=false",
    "estoma.messaging.topology-declare=false",
    "spring.task.scheduling.enabled=false"
})
@Testcontainers
@Tag("integration")
class AcademicPeriodReadinessIT {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("academic_db")
      .withUsername("academic_service")
      .withPassword("academic_service");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired JdbcClient jdbc;
  @Autowired AcademicCommandDispatcher dispatcher;
  @Autowired ObjectMapper mapper;

  UUID admin = UUID.randomUUID();
  UUID adminPerson = UUID.randomUUID();
  UUID period = UUID.randomUUID();

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE outbox_messages,processed_messages,idempotency_records,student_import_validation_errors,student_import_plan_items,student_import_processes,student_course_sections,course_sections,student_academic_eligibilities,student_number_change_history,student_academic_profiles,academic_periods,person_status_projection,account_status_projection,authorization_context_projection CASCADE").update();
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:id,'ACTIVE',1,now())")
        .param("id", adminPerson)
        .update();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:id,:person,'USERNAME','ACTIVE',1,now())")
        .param("id", admin)
        .param("person", adminPerson)
        .update();
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:id,'SUPERADMIN','ACTIVE',1,now())")
        .param("id", admin)
        .update();
    jdbc.sql("INSERT INTO academic_periods (academic_period_id,code,name,start_date,end_date,status,academic_data_status,version,created_at,updated_at) VALUES (:id,'2026-1','Spring',current_date,current_date,'ACTIVE','PENDING_IMPORT',1,now(),now())")
        .param("id", period)
        .update();
  }

  @Test
  void pendingPeriodRejectsSectionWithAcademicPeriodNotReady() {
    assertThat(dispatcher.dispatch(command("RegisterCourseSection", mapper.createObjectNode()
        .put("academicPeriodId", period.toString())
        .put("nrc", "NRC-1")
        .put("name", "Clinical"))).errorCode()).isEqualTo("ACADEMIC_PERIOD_NOT_READY");
  }

  @Test
  void readyFixtureAllowsSectionRegistration() {
    jdbc.sql("UPDATE academic_periods SET academic_data_status='READY' WHERE academic_period_id=:id")
        .param("id", period)
        .update();
    assertThat(dispatcher.dispatch(command("RegisterCourseSection", mapper.createObjectNode()
        .put("academicPeriodId", period.toString())
        .put("nrc", "NRC-1")
        .put("name", "Clinical"))).status()).isEqualTo("SUCCEEDED");
  }

  @Test
  void closedPeriodRejectsBeforeReadiness() {
    jdbc.sql("UPDATE academic_periods SET status='CLOSED' WHERE academic_period_id=:id")
        .param("id", period)
        .update();
    assertThat(dispatcher.dispatch(command("RegisterCourseSection", mapper.createObjectNode()
        .put("academicPeriodId", period.toString())
        .put("nrc", "NRC-1")
        .put("name", "Clinical"))).errorCode()).isEqualTo("ACADEMIC_PERIOD_CLOSED");
  }

  @Test
  void inactiveAdminAccountFailsClosed() {
    jdbc.sql("UPDATE account_status_projection SET status='INACTIVE' WHERE account_id=:id")
        .param("id", admin)
        .update();
    assertThat(dispatcher.dispatch(command("RegisterCourseSection", mapper.createObjectNode()
        .put("academicPeriodId", period.toString())
        .put("nrc", "NRC-1")
        .put("name", "Clinical"))).errorCode()).isEqualTo("ACTOR_NOT_AUTHORIZED");
  }

  @Test
  void inactiveAdminPersonFailsClosed() {
    jdbc.sql("UPDATE person_status_projection SET status='INACTIVE' WHERE person_id=:id")
        .param("id", adminPerson)
        .update();
    assertThat(dispatcher.dispatch(command("RegisterCourseSection", mapper.createObjectNode()
        .put("academicPeriodId", period.toString())
        .put("nrc", "NRC-1")
        .put("name", "Clinical"))).errorCode()).isEqualTo("ACTOR_NOT_AUTHORIZED");
  }

  @Test
  void acceptsImportWithFrozenContextAndBlocksPeriodChangesWhileItValidates() {
    UUID importId = UUID.randomUUID();
    assertThat(dispatcher.dispatch(command("StartStudentImport", mapper.createObjectNode()
        .put("importId", importId.toString())
        .put("fileReference", importId.toString())
        .put("fileChecksum", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        .put("requestedByAccountId", admin.toString()))).status()).isEqualTo("SUCCEEDED");

    assertThat(jdbc.sql("SELECT status::text FROM student_import_processes WHERE import_id=:id")
        .param("id", importId)
        .query(String.class)
        .single()).isEqualTo("VALIDATING");
    assertThat(jdbc.sql("SELECT academic_period_id FROM student_import_processes WHERE import_id=:id")
        .param("id", importId)
        .query(UUID.class)
        .single()).isEqualTo(period);
    assertThat(dispatcher.dispatch(command("CreateAndActivateAcademicPeriod", mapper.createObjectNode()
        .put("code", "2026-2")
        .put("name", "Summer")
        .put("startDate", "2026-06-01")
        .put("endDate", "2026-08-31")
        .put("requestedByAccountId", admin.toString())
        .put("warningAcknowledged", true))).errorCode()).isEqualTo("ACADEMIC_PERIOD_CHANGE_BLOCKED_BY_IMPORT");
  }

  @Test
  void startImportIsIdempotentOnlyForTheSameFrozenFile() {
    UUID importId = UUID.randomUUID();
    var payload = mapper.createObjectNode()
        .put("importId", importId.toString())
        .put("fileReference", importId.toString())
        .put("fileChecksum", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        .put("requestedByAccountId", admin.toString());
    assertThat(dispatcher.dispatch(command("StartStudentImport", payload.deepCopy())).status()).isEqualTo("SUCCEEDED");
    assertThat(dispatcher.dispatch(command("StartStudentImport", payload.deepCopy())).status()).isEqualTo("SUCCEEDED");
    assertThat(jdbc.sql("SELECT count(*) FROM student_import_processes WHERE import_id=:id")
        .param("id", importId)
        .query(Long.class)
        .single()).isEqualTo(1);

    payload.put("fileChecksum", "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210");
    assertThat(dispatcher.dispatch(command("StartStudentImport", payload)).errorCode()).isEqualTo("IMPORT_ALREADY_EXISTS");
  }

  @Test
  void enrollmentEventsUseDistinctStableStreamsForTwoCourseSections() {
    jdbc.sql("UPDATE academic_periods SET academic_data_status='READY' WHERE academic_period_id=:id")
        .param("id", period)
        .update();
    UUID profile = UUID.randomUUID();
    jdbc.sql("INSERT INTO student_academic_profiles (student_academic_profile_id,person_id,account_id,student_number,student_number_normalized,current_semester,academic_status,version,student_number_version,created_at,updated_at) VALUES (:id,:person,:account,'ST-ENROLL','ST-ENROLL',5,'ACTIVE',1,0,now(),now())")
        .param("id", profile)
        .param("person", UUID.randomUUID())
        .param("account", UUID.randomUUID())
        .update();
    UUID firstSection = UUID.randomUUID();
    UUID secondSection = UUID.randomUUID();
    jdbc.sql("INSERT INTO course_sections (course_section_id,academic_period_id,nrc,name,status,version,created_at,updated_at) VALUES (:id,:period,:nrc,:name,'ACTIVE',1,now(),now()),(:id2,:period,:nrc2,:name2,'ACTIVE',1,now(),now())")
        .param("id", firstSection)
        .param("period", period)
        .param("nrc", "NRC-1")
        .param("name", "Clinical 1")
        .param("id2", secondSection)
        .param("nrc2", "NRC-2")
        .param("name2", "Clinical 2")
        .update();

    assertThat(dispatcher.dispatch(command("AddStudentEnrollment", mapper.createObjectNode()
        .put("studentAcademicProfileId", profile.toString())
        .put("courseSectionId", firstSection.toString()))).status()).isEqualTo("SUCCEEDED");
    assertThat(dispatcher.dispatch(command("AddStudentEnrollment", mapper.createObjectNode()
        .put("studentAcademicProfileId", profile.toString())
        .put("courseSectionId", secondSection.toString()))).status()).isEqualTo("SUCCEEDED");

    var streams = jdbc.sql("SELECT aggregate_id,aggregate_version FROM outbox_messages WHERE message_type='StudentEnrolledInCourseSection' ORDER BY occurred_at")
        .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getInt(2)})
        .list();
    assertThat(streams).hasSize(2);
    assertThat(streams.get(0)[0]).isNotEqualTo(streams.get(1)[0]);
    assertThat(streams.get(0)[1]).isEqualTo(1);
    assertThat(streams.get(1)[1]).isEqualTo(1);
  }

  private MessageEnvelope command(String type, tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, "COMMAND", "bff", Instant.now(), UUID.randomUUID(), null,
        UUID.randomUUID(), UUID.randomUUID().toString(), null, null,
        new Actor("ACCOUNT", admin, null), payload);
  }
}

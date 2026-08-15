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
class StudentImportResumeGuardRebaserIT {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("academic_db")
      .withUsername("academic_service")
      .withPassword("academic_service");

  static { POSTGRES.start(); }

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
  @Autowired AcademicCommandDispatcher commands;
  @Autowired ObjectMapper mapper;

  UUID period;
  UUID admin;

  @BeforeEach
  void reset() {
    jdbc.sql("""
        TRUNCATE TABLE
          outbox_messages,processed_messages,idempotency_records,
          student_import_resume_audit,student_import_validation_work,student_import_validation_rows,
          student_import_validation_errors,student_import_plan_item_rebases,student_import_plan_items,
          student_import_processes,student_course_sections,course_sections,
          student_academic_eligibilities,student_academic_profiles,academic_periods,
          person_status_projection,account_status_projection,authorization_context_projection,
          system_access_context_projection CASCADE
        """).update();
    period = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO academic_periods (
          academic_period_id,code,name,start_date,end_date,status,academic_data_status,version,created_at,updated_at)
        VALUES (:id,'2026-1','Spring',current_date,current_date,'ACTIVE','IMPORTING',2,now(),now())
        """).param("id", period).update();
    admin = seedAdmin();
  }

  @Test
  void compatibleAccountVersionDriftRebasesOnlyTheTechnicalGuard() {
    UUID person = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    seedStudentAccount(person, account, "ACTIVE", 5, null);
    UUID importId = failedProcess();
    UUID itemId = failedDeactivateAccount(importId, person, account, 4);

    AcademicCommandDispatcher.Outcome outcome = commands.dispatch(resume(importId, "resume-safe:" + importId));

    assertThat(outcome.status()).isEqualTo("SUCCEEDED");
    assertThat(jdbc.sql("""
        SELECT (expected_versions->>'accountVersion')::int FROM student_import_plan_items
        WHERE import_id=:import AND plan_item_id=:item
        """).param("import", importId).param("item", itemId).query(Integer.class).single()).isEqualTo(5);
    assertThat(jdbc.sql("""
        SELECT status::text FROM student_import_plan_items
        WHERE import_id=:import AND plan_item_id=:item
        """).param("import", importId).param("item", itemId).query(String.class).single()).isEqualTo("PENDING");
    assertThat(jdbc.sql("SELECT execution_generation FROM student_import_plan_items WHERE import_id=:id")
        .param("id", importId).query(Integer.class).single()).isEqualTo(2);
    assertThat(jdbc.sql("SELECT count(*) FROM student_import_plan_item_rebases WHERE import_id=:id")
        .param("id", importId).query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("""
        SELECT (previous_expected_versions->>'accountVersion')::int FROM student_import_plan_item_rebases
        WHERE import_id=:id
        """).param("id", importId).query(Integer.class).single()).isEqualTo(4);
    assertThat(jdbc.sql("""
        SELECT (new_expected_versions->>'accountVersion')::int FROM student_import_plan_item_rebases
        WHERE import_id=:id
        """).param("id", importId).query(Integer.class).single()).isEqualTo(5);
    assertThat(jdbc.sql("SELECT resolved_by_account_id FROM student_import_plan_item_rebases WHERE import_id=:id")
        .param("id", importId).query(UUID.class).single()).isEqualTo(admin);
  }

  @Test
  void securityDeactivationCannotBeRebasedOverAcademicAbsencePlan() {
    UUID person = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    seedStudentAccount(person, account, "INACTIVE", 5, "SECURITY");
    UUID importId = failedProcess();
    UUID itemId = failedDeactivateAccount(importId, person, account, 4);

    AcademicCommandDispatcher.Outcome outcome = commands.dispatch(resume(importId, "resume-security:" + importId));

    assertThat(outcome.status()).isEqualTo("REJECTED");
    assertThat(outcome.errorCode()).isEqualTo("IMPORT_RESUME_NOT_ALLOWED");
    assertThat(jdbc.sql("SELECT status::text FROM student_import_processes WHERE import_id=:id")
        .param("id", importId).query(String.class).single()).isEqualTo("APPLICATION_FAILED");
    assertThat(jdbc.sql("""
        SELECT (expected_versions->>'accountVersion')::int FROM student_import_plan_items
        WHERE import_id=:import AND plan_item_id=:item
        """).param("import", importId).param("item", itemId).query(Integer.class).single()).isEqualTo(4);
    assertThat(jdbc.sql("SELECT count(*) FROM student_import_plan_item_rebases WHERE import_id=:id")
        .param("id", importId).query(Long.class).single()).isZero();
  }

  private UUID failedProcess() {
    UUID importId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO student_import_processes (
          import_id,academic_period_id,academic_period_code,academic_period_version,import_mode,file_reference,
          file_checksum,status,started_by_account_id,authorized_by_account_id,process_version,execution_generation,
          started_at,application_started_at,failed_at,failed_step,failure_code,recoverable,created_at,updated_at)
        VALUES (:id,:period,'2026-1',1,'FULL',:ref,
          '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
          'APPLICATION_FAILED',:actor,:actor,5,1,now(),now(),now(),'DEACTIVATE_ACCOUNT','ACCOUNT_VERSION_CONFLICT',true,now(),now())
        """).param("id", importId).param("period", period).param("ref", UUID.randomUUID().toString())
        .param("actor", actor).update();
    return importId;
  }

  private UUID failedDeactivateAccount(UUID importId, UUID person, UUID account, int expectedVersion) {
    UUID itemId = UUID.randomUUID();
    var effect = mapper.createObjectNode()
        .put("accountId", account.toString())
        .put("personId", person.toString())
        .put("reasonCode", "ACADEMIC_ABSENCE");
    var versions = mapper.createObjectNode().put("accountVersion", expectedVersion);
    jdbc.sql("""
        INSERT INTO student_import_plan_items (
          import_id,plan_item_id,item_type,business_key,planned_effect,expected_versions,
          execution_generation,status,last_result_status,last_error_code,created_at,updated_at)
        VALUES (:import,:item,'DEACTIVATE_ACCOUNT',CAST(:key AS jsonb),CAST(:effect AS jsonb),
          CAST(:versions AS jsonb),1,'FAILED','REJECTED','ACCOUNT_VERSION_CONFLICT',now(),now())
        """).param("import", importId).param("item", itemId)
        .param("key", mapper.createObjectNode().put("accountId", account.toString()).toString())
        .param("effect", effect.toString()).param("versions", versions.toString()).update();
    return itemId;
  }

  private void seedStudentAccount(UUID person, UUID account, String status, int version, String reason) {
    jdbc.sql("""
        INSERT INTO person_status_projection (person_id,status,source_version,updated_at)
        VALUES (:person,'ACTIVE',1,now())
        """).param("person", person).update();
    jdbc.sql("""
        INSERT INTO account_status_projection (
          account_id,person_id,login_type,status,last_deactivation_reason_code,source_version,updated_at)
        VALUES (:account,:person,'MATRICULA',:status,:reason,:version,now())
        """).param("account", account).param("person", person).param("status", status)
        .param("reason", reason).param("version", version).update();
  }

  private UUID seedAdmin() {
    UUID person = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:id,'ACTIVE',1,now())")
        .param("id", person).update();
    jdbc.sql("""
        INSERT INTO account_status_projection (
          account_id,person_id,login_type,status,source_version,updated_at)
        VALUES (:id,:person,'USERNAME','ACTIVE',1,now())
        """).param("id", account).param("person", person).update();
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:id,'SUPERADMIN','ACTIVE',1,now())")
        .param("id", account).update();
    return account;
  }

  private MessageEnvelope resume(UUID importId, String key) {
    return new MessageEnvelope(
        UUID.randomUUID(), "ResumeStudentImport", 1, "COMMAND", "bff", Instant.now(),
        UUID.randomUUID(), null, UUID.randomUUID(), key, importId, 5,
        new Actor("ACCOUNT", admin, null),
        mapper.createObjectNode().put("importId", importId.toString()).put("requestedByAccountId", admin.toString()));
  }
}

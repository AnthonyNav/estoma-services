package com.estoma.academic;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.academic.application.StudentImportPlanBuilder;
import com.estoma.academic.application.StudentImportPlanConvergenceWorker;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class StudentImportPlanBuilderIT {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("academic_db").withUsername("academic_service").withPassword("academic_service");

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
  @Autowired ObjectMapper mapper;
  @Autowired StudentImportPlanBuilder planBuilder;
  @Autowired StudentImportPlanConvergenceWorker convergenceWorker;

  UUID period;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE outbox_messages,student_import_validation_work,student_import_validation_rows,student_import_plan_item_rebases,student_import_plan_items,student_import_processes,student_course_sections,course_sections,student_academic_profiles,academic_periods,person_status_projection,account_status_projection,authorization_context_projection,system_access_context_projection CASCADE").update();
    period = UUID.randomUUID();
    jdbc.sql("INSERT INTO academic_periods (academic_period_id,code,name,start_date,end_date,status,academic_data_status,version,created_at,updated_at) VALUES (:id,'2026-1','Spring',current_date,current_date,'ACTIVE','PENDING_IMPORT',1,now(),now())")
        .param("id", period).update();
  }

  @Test
  void persistsFixtureOutcomesThenBuildsOneDeterministicCompletePlanWithoutChangingOfficialData() {
    UUID importId = importWithRows();
    UUID bob = UUID.randomUUID();
    UUID bobAccount = UUID.randomUUID();
    UUID bobProfile = UUID.randomUUID();
    UUID absentPerson = UUID.randomUUID();
    UUID absentAccount = UUID.randomUUID();
    UUID absentProfile = UUID.randomUUID();
    UUID oldSection = UUID.randomUUID();
    UUID oldEnrollment = UUID.randomUUID();
    projection(bob, bobAccount, 7, 8);
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:id,'ALUMNO','ACTIVE',2,now())").param("id", bobAccount).update();
    jdbc.sql("INSERT INTO system_access_context_projection (account_id,system_code,status,source_version,updated_at) VALUES (:id,'LAVADO_ULTRASONICO','ACTIVE',3,now())").param("id", bobAccount).update();
    profile(bobProfile, bob, bobAccount, "ST-2", 3, "INACTIVE", 5);
    projection(absentPerson, absentAccount, 2, 4);
    profile(absentProfile, absentPerson, absentAccount, "ST-OLD", 2, "ACTIVE", 6);
    jdbc.sql("INSERT INTO course_sections (course_section_id,academic_period_id,nrc,name,status,version,created_at,updated_at) VALUES (:id,:period,'NRC-OLD','Old','ACTIVE',4,now(),now())")
        .param("id", oldSection).param("period", period).update();
    jdbc.sql("INSERT INTO student_course_sections (enrollment_id,student_academic_profile_id,course_section_id,status,enrolled_at,version,updated_at) VALUES (:id,:profile,:section,'ACTIVE',now(),9,now())")
        .param("id", oldEnrollment).param("profile", absentProfile).param("section", oldSection).update();

    resolution(importId, "alice@estoma.mx", "{\"outcome\":\"NOT_FOUND\"}");
    resolution(importId, "bob@estoma.mx", "{\"outcome\":\"FOUND\",\"personId\":\"" + bob + "\",\"status\":\"ACTIVE\"}");

    assertThat(planBuilder.buildIfReady(importId)).isTrue();
    long planCount = count("student_import_plan_items", importId);
    assertThat(planBuilder.buildIfReady(importId)).isTrue();
    assertThat(count("student_import_plan_items", importId)).isEqualTo(planCount);
    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId)).isEqualTo("VALIDATED");
    assertSummary(map("""
        SELECT json_build_object(
          'studentsCreated',students_created,'studentsUpdated',students_updated,
          'studentsReactivated',students_reactivated,'studentsDeactivated',students_deactivated,
          'courseSectionsCreated',course_sections_created,'courseSectionsActivated',course_sections_activated,
          'courseSectionsDeactivated',course_sections_deactivated,'enrollmentsRegistered',enrollments_registered,
          'enrollmentsReactivated',enrollments_reactivated,'enrollmentsRemoved',enrollments_removed)::text
        FROM student_import_processes WHERE import_id=:id
        """, importId));
    var validated = map("SELECT payload::text FROM outbox_messages WHERE message_type='StudentImportValidated' AND aggregate_id=:id", importId);
    assertSummary(validated);
    assertThat(validated.path("importId").asString()).isEqualTo(importId.toString());
    assertThat(validated.path("academicPeriodId").asString()).isEqualTo(period.toString());
    assertThat(validated.path("validatedAt").isTextual()).isTrue();
    assertThat(jdbc.sql("SELECT aggregate_version FROM outbox_messages WHERE message_type='StudentImportValidated' AND aggregate_id=:id")
        .param("id", importId).query(Integer.class).single()).isEqualTo(2);
    assertThat(types(importId)).contains(
        "REGISTER_COURSE_SECTION", "DEACTIVATE_COURSE_SECTION", "CREATE_PERSON", "CREATE_ACCOUNT",
        "ASSIGN_STUDENT_ROLE", "GRANT_STUDENT_SYSTEM_ACCESS", "CREATE_STUDENT_PROFILE", "UPDATE_PERSON",
        "ACTIVATE_STUDENT_PROFILE", "CHANGE_STUDENT_SEMESTER", "DEACTIVATE_STUDENT_PROFILE",
        "DEACTIVATE_ACCOUNT", "ADD_STUDENT_ENROLLMENT", "REMOVE_STUDENT_ENROLLMENT", "FINALIZE_STUDENT_IMPORT")
        .doesNotContain("GRANT_STUDENT_SYSTEM_ACCESS:PRACTICAS_PROFESIONALES");
    assertThat(jdbc.sql("SELECT count(*) FROM student_import_plan_items WHERE import_id=:id AND item_type IN ('CREATE_PERSON','CREATE_ACCOUNT') AND generated_entity_id IS NOT NULL")
        .param("id", importId).query(Long.class).single()).isEqualTo(2);
    assertThat(value("SELECT expected_versions->>'personVersion' FROM student_import_plan_items WHERE import_id=:id AND item_type='UPDATE_PERSON'", importId)).isEqualTo("7");
    assertThat(value("SELECT planned_effect->>'institutionalEmail' FROM student_import_plan_items WHERE import_id=:id AND item_type='UPDATE_PERSON'", importId)).isEqualTo("bob@estoma.mx");
    assertThat(value("SELECT academic_status::text FROM student_academic_profiles WHERE student_academic_profile_id=:id", bobProfile)).isEqualTo("INACTIVE");
    assertThat(value("SELECT status::text FROM course_sections WHERE course_section_id=:id", oldSection)).isEqualTo("ACTIVE");
  }

  @Test
  void authoritativeResultWaitsForPersonProjectionThenConvergesWithoutFreezingAnUnversionedUpdate() {
    UUID importId = importWithRows();
    UUID bob = UUID.randomUUID();
    resolution(importId, "alice@estoma.mx", "{\"outcome\":\"NOT_FOUND\"}");
    resolution(importId, "bob@estoma.mx", "{\"outcome\":\"FOUND\",\"personId\":\"" + bob + "\",\"status\":\"ACTIVE\"}");

    assertThat(planBuilder.buildIfReady(importId)).isFalse();
    assertThat(count("student_import_plan_items", importId)).isZero();
    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId)).isEqualTo("VALIDATING");

    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:id,'ACTIVE',11,now())")
        .param("id", bob).update();
    convergenceWorker.convergeReadyPlans();

    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId)).isEqualTo("VALIDATED");
    assertThat(value("SELECT expected_versions->>'personVersion' FROM student_import_plan_items WHERE import_id=:id AND item_type='UPDATE_PERSON'", importId)).isEqualTo("11");
    assertThat(value("SELECT planned_effect->>'institutionalEmail' FROM student_import_plan_items WHERE import_id=:id AND item_type='UPDATE_PERSON'", importId)).isEqualTo("bob@estoma.mx");
  }

  private UUID importWithRows() {
    UUID importId = importProcess();
    row(importId, "NRC", 1, Map.of("nrc", "NRC-NEW", "name", "New"));
    row(importId, "STUDENT", 1, Map.of("studentNumber", "ST-1", "firstName", "Alice", "paternalSurname", "Doe", "maternalSurname", "", "institutionalEmail", "alice@estoma.mx", "semester", 1));
    row(importId, "STUDENT", 2, Map.of("studentNumber", "ST-2", "firstName", "Bob", "paternalSurname", "Smith", "maternalSurname", "", "institutionalEmail", "bob@estoma.mx", "semester", 4));
    row(importId, "ENROLLMENT", 1, Map.of("studentNumber", "ST-1", "nrc", "NRC-NEW"));
    return importId;
  }

  private UUID importProcess() {
    UUID importId = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.sql("INSERT INTO student_import_processes (import_id,academic_period_id,academic_period_code,academic_period_version,import_mode,file_reference,file_checksum,status,started_by_account_id,authorized_by_account_id,process_version,execution_generation,started_at,created_at,updated_at) VALUES (:id,:period,'2026-1',1,'FULL',:reference,'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef','VALIDATING',:actor,:actor,1,0,:now,:now,:now)")
        .param("id", importId).param("period", period).param("reference", UUID.randomUUID().toString()).param("actor", UUID.randomUUID()).param("now", java.sql.Timestamp.from(now)).update();
    jdbc.sql("INSERT INTO student_import_validation_work (import_id,work_id,work_type,work_key,status,created_at,updated_at) VALUES (:id,:work,'LOCAL_FILE_ACCESS','file','SUCCEEDED',now(),now())")
        .param("id", importId).param("work", UUID.randomUUID()).update();
    return importId;
  }

  private void resolution(UUID importId, String email, String result) {
    jdbc.sql("INSERT INTO student_import_validation_work (import_id,work_id,work_type,work_key,status,result,created_at,updated_at) VALUES (:id,:work,'AUTHORITATIVE_PERSON_RESOLUTION',:email,'SUCCEEDED',CAST(:result AS jsonb),now(),now())")
        .param("id", importId).param("work", UUID.randomUUID()).param("email", email).param("result", result).update();
  }

  private void row(UUID importId, String type, int sequence, Map<String, Object> data) {
    jdbc.sql("INSERT INTO student_import_validation_rows (import_id,row_type,sequence,sheet_name,row_number,row_data,created_at) VALUES (:id,:type,:sequence,:type,:row,CAST(:data AS jsonb),now())")
        .param("id", importId).param("type", type).param("sequence", sequence).param("row", sequence + 1).param("data", mapper.writeValueAsString(data)).update();
  }

  private void projection(UUID person, UUID account, int personVersion, int accountVersion) {
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:person,'ACTIVE',:version,now())").param("person", person).param("version", personVersion).update();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:account,:person,'MATRICULA','ACTIVE',:version,now())").param("account", account).param("person", person).param("version", accountVersion).update();
  }

  private void profile(UUID id, UUID person, UUID account, String number, int semester, String status, int version) {
    jdbc.sql("INSERT INTO student_academic_profiles (student_academic_profile_id,person_id,account_id,student_number,student_number_normalized,current_semester,academic_status,version,student_number_version,created_at,updated_at) VALUES (:id,:person,:account,:number,:normalized,:semester,CAST(:status AS academic_profile_status),:version,0,now(),now())")
        .param("id", id).param("person", person).param("account", account).param("number", number).param("normalized", number).param("semester", semester).param("status", status).param("version", version).update();
  }

  private String value(String sql, UUID id) { return jdbc.sql(sql).param("id", id).query(String.class).single(); }
  private tools.jackson.databind.JsonNode map(String sql, UUID id) { return mapper.readTree(jdbc.sql(sql).param("id", id).query(String.class).single()); }
  private static void assertSummary(tools.jackson.databind.JsonNode summary) {
    assertThat(summary.path("studentsCreated").asInt()).isEqualTo(1);
    assertThat(summary.path("studentsUpdated").asInt()).isEqualTo(1);
    assertThat(summary.path("studentsReactivated").asInt()).isEqualTo(1);
    assertThat(summary.path("studentsDeactivated").asInt()).isEqualTo(1);
    assertThat(summary.path("courseSectionsCreated").asInt()).isEqualTo(1);
    assertThat(summary.path("courseSectionsActivated").asInt()).isZero();
    assertThat(summary.path("courseSectionsDeactivated").asInt()).isEqualTo(1);
    assertThat(summary.path("enrollmentsRegistered").asInt()).isEqualTo(1);
    assertThat(summary.path("enrollmentsReactivated").asInt()).isZero();
    assertThat(summary.path("enrollmentsRemoved").asInt()).isEqualTo(1);
  }
  private long count(String table, UUID importId) { return jdbc.sql("SELECT count(*) FROM " + table + " WHERE import_id=:id").param("id", importId).query(Long.class).single(); }
  private List<String> types(UUID importId) { return jdbc.sql("SELECT item_type::text FROM student_import_plan_items WHERE import_id=:id").param("id", importId).query(String.class).list(); }
}

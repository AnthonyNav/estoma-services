package com.estoma.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.academic.application.AcademicCommandDispatcher;
import com.estoma.academic.application.StudentImportApplicationManager;
import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "estoma.messaging.listeners-enabled=false",
    "estoma.messaging.topology-declare=false",
    "spring.task.scheduling.enabled=false"
})
@Testcontainers
@Tag("integration")
class StudentImportApplicationManagerIT {
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
  @Autowired ObjectMapper mapper;
  @Autowired StudentImportApplicationManager manager;
  @Autowired AcademicCommandDispatcher commands;

  UUID period;

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
        VALUES (:id,'2026-1','Spring',current_date,current_date,'ACTIVE','IMPORTING',1,now(),now())
        """).param("id", period).update();
  }

  @Test
  void validatedImportStartsAutomaticallyAndBffCannotInvokeApply() {
    UUID importId = process("VALIDATED", 0, 1);
    jdbc.sql("UPDATE academic_periods SET academic_data_status='PENDING_IMPORT' WHERE academic_period_id=:id")
        .param("id", period).update();
    UUID personId = UUID.randomUUID();
    UUID plan = item(importId, "CREATE_PERSON", Map.of(
        "personId", personId,
        "institutionalEmail", "student@estoma.mx",
        "firstName", "Student",
        "paternalSurname", "One"));

    manager.schedule();

    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId))
        .isEqualTo("APPLYING");
    assertThat(value("SELECT academic_data_status::text FROM academic_periods WHERE academic_period_id=:id", period))
        .isEqualTo("IMPORTING");
    assertThat(integer("SELECT execution_generation FROM student_import_processes WHERE import_id=:id", importId))
        .isEqualTo(1);
    assertThat(integer("SELECT execution_generation FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + plan + "'", importId))
        .isEqualTo(1);
    assertThat(types(importId)).contains("StudentImportApplicationStarted", "StudentImportProgressUpdated");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AcademicPeriodDataStatusChanged' AND aggregate_id=:id")
        .param("id", period).query(Long.class).single()).isEqualTo(1);

    UUID admin = admin();
    assertThatThrownBy(() -> commands.dispatch(command("ApplyStudentImport", importId, admin, UUID.randomUUID(), "apply:" + importId)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void localAcademicEffectsEmitDomainEventsEligibilityAndRespectRemovalBeforeNrcDeactivation() {
    UUID importId = process("APPLYING", 1, 1);
    UUID accountId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    UUID sectionId = UUID.randomUUID();
    UUID enrollmentId = UUID.randomUUID();

    jdbc.sql("""
        INSERT INTO student_academic_profiles (
          student_academic_profile_id,person_id,account_id,student_number,student_number_normalized,
          current_semester,academic_status,version,student_number_version,created_at,updated_at)
        VALUES (:id,:person,:account,'ST-1','ST-1',5,'ACTIVE',1,0,now(),now())
        """).param("id", profileId).param("person", UUID.randomUUID()).param("account", accountId).update();
    jdbc.sql("""
        INSERT INTO course_sections (
          course_section_id,academic_period_id,nrc,name,status,version,created_at,updated_at)
        VALUES (:id,:period,'NRC-OLD','Old','ACTIVE',1,now(),now())
        """).param("id", sectionId).param("period", period).update();
    jdbc.sql("""
        INSERT INTO student_course_sections (
          enrollment_id,student_academic_profile_id,course_section_id,status,enrolled_at,version,updated_at)
        VALUES (:id,:profile,:section,'ACTIVE',now(),1,now())
        """).param("id", enrollmentId).param("profile", profileId).param("section", sectionId).update();

    item(importId, "CHANGE_STUDENT_SEMESTER",
        Map.of("studentAcademicProfileId", profileId, "currentSemester", 6),
        Map.of("studentAcademicProfileVersion", 1));
    item(importId, "REMOVE_STUDENT_ENROLLMENT",
        Map.of("enrollmentId", enrollmentId, "studentAcademicProfileId", profileId, "courseSectionId", sectionId),
        Map.of("enrollmentVersion", 1));
    item(importId, "DEACTIVATE_COURSE_SECTION",
        Map.of("courseSectionId", sectionId, "academicPeriodId", period, "nrc", "NRC-OLD"),
        Map.of("courseSectionVersion", 1));

    manager.schedule();
    assertThat(integer("SELECT current_semester FROM student_academic_profiles WHERE student_academic_profile_id=:id", profileId))
        .isEqualTo(6);
    assertThat(jdbc.sql("SELECT message_type FROM outbox_messages WHERE aggregate_id=:id ORDER BY occurred_at")
        .param("id", profileId).query(String.class).list()).contains("StudentSemesterChanged");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='StudentAcademicEligibilityChanged'")
        .query(Long.class).single()).isEqualTo(1);

    manager.schedule();
    assertThat(value("SELECT status::text FROM student_course_sections WHERE enrollment_id=:id", enrollmentId))
        .isEqualTo("REMOVED");
    assertThat(jdbc.sql("SELECT removed_at IS NOT NULL FROM student_course_sections WHERE enrollment_id=:id")
        .param("id", enrollmentId).query(Boolean.class).single()).isTrue();
    assertThat(value("SELECT status::text FROM course_sections WHERE course_section_id=:id", sectionId))
        .isEqualTo("ACTIVE");

    manager.schedule();
    assertThat(value("SELECT status::text FROM course_sections WHERE course_section_id=:id", sectionId))
        .isEqualTo("INACTIVE");
    assertThat(types(importId)).contains("StudentSemesterChanged", "StudentAcademicEligibilityChanged",
        "StudentRemovedFromCourseSection", "CourseSectionStatusChanged");
  }

  @Test
  void inactiveProfileActivatesBeforeItsSemesterChange() {
    UUID importId = process("APPLYING", 1, 1);
    UUID profileId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO student_academic_profiles (
          student_academic_profile_id,person_id,account_id,student_number,student_number_normalized,
          current_semester,academic_status,version,student_number_version,created_at,updated_at)
        VALUES (:id,:person,:account,'ST-INACTIVE','ST-INACTIVE',5,'INACTIVE',4,0,now(),now())
        """).param("id", profileId).param("person", UUID.randomUUID()).param("account", accountId).update();

    UUID activate = item(importId, "ACTIVATE_STUDENT_PROFILE",
        Map.of("studentAcademicProfileId", profileId),
        Map.of("studentAcademicProfileVersion", 4));
    UUID semester = item(importId, "CHANGE_STUDENT_SEMESTER",
        Map.of("studentAcademicProfileId", profileId, "currentSemester", 6),
        Map.of("studentAcademicProfileVersion", 5));

    manager.schedule();
    assertThat(value("SELECT academic_status::text FROM student_academic_profiles WHERE student_academic_profile_id=:id", profileId))
        .isEqualTo("ACTIVE");
    assertThat(integer("SELECT current_semester FROM student_academic_profiles WHERE student_academic_profile_id=:id", profileId))
        .isEqualTo(5);
    assertThat(value("SELECT status::text FROM student_import_plan_items WHERE plan_item_id='" + activate + "' AND import_id=:id", importId))
        .isEqualTo("COMPLETED");
    assertThat(value("SELECT status::text FROM student_import_plan_items WHERE plan_item_id='" + semester + "' AND import_id=:id", importId))
        .isEqualTo("PENDING");

    manager.schedule();
    assertThat(integer("SELECT current_semester FROM student_academic_profiles WHERE student_academic_profile_id=:id", profileId))
        .isEqualTo(6);
    assertThat(integer("SELECT version FROM student_academic_profiles WHERE student_academic_profile_id=:id", profileId))
        .isEqualTo(6);
    assertThat(value("SELECT status::text FROM student_import_plan_items WHERE plan_item_id='" + semester + "' AND import_id=:id", importId))
        .isEqualTo("COMPLETED");
  }

  @Test
  void remoteChainCarriesCausalityAndRetriesTheSameOperationAndIdempotencyKey() {
    UUID importId = process("APPLYING", 1, 1);
    UUID personId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID personItem = item(importId, "CREATE_PERSON", Map.of(
        "personId", personId, "institutionalEmail", "student@estoma.mx",
        "firstName", "Student", "paternalSurname", "One"));
    UUID accountItem = item(importId, "CREATE_ACCOUNT", Map.of(
        "accountId", accountId, "personId", personId, "loginType", "MATRICULA",
        "loginIdentifier", "ST-1", "status", "INACTIVE"));

    manager.schedule();
    UUID personMessage = uuidValue("SELECT current_command_message_id FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + personItem + "'", importId);
    UUID personOperation = uuidValue("SELECT current_operation_id FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + personItem + "'", importId);
    MessageEnvelope personResult = result(
        "CreatePersonResult", "users", importId, personItem, personMessage, personOperation, 1, "SUCCEEDED", null);
    assertThat(manager.applyResult(personResult)).isTrue();

    manager.schedule();
    JsonNode accountPayload = mapper.readTree(jdbc.sql("""
        SELECT payload::text FROM outbox_messages
        WHERE message_type='CreateAccount' ORDER BY occurred_at DESC LIMIT 1
        """).query(String.class).single());
    JsonNode dependency = accountPayload.path("importExecution").path("causalDependencies").get(0);
    assertThat(dependency.path("priorCommandType").asString()).isEqualTo("CreatePerson");
    assertThat(dependency.path("priorResultMessageId").asString()).isEqualTo(personResult.messageId().toString());
    assertThat(dependency.path("expectedEventType").asString()).isEqualTo("PersonCreated");
    UUID accountMessage1 = uuidValue("SELECT current_command_message_id FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + accountItem + "'", importId);
    UUID accountOperation1 = uuidValue("SELECT current_operation_id FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + accountItem + "'", importId);
    String accountKey1 = value("SELECT current_idempotency_key FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + accountItem + "'", importId);

    jdbc.sql("UPDATE student_import_plan_items SET next_attempt_at=now()-interval '1 second' WHERE import_id=:id AND plan_item_id=:item")
        .param("id", importId).param("item", accountItem).update();
    manager.schedule();
    UUID accountMessage2 = uuidValue("SELECT current_command_message_id FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + accountItem + "'", importId);
    UUID accountOperation2 = uuidValue("SELECT current_operation_id FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + accountItem + "'", importId);
    String accountKey2 = value("SELECT current_idempotency_key FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + accountItem + "'", importId);
    assertThat(accountMessage2).isNotEqualTo(accountMessage1);
    assertThat(accountOperation2).isEqualTo(accountOperation1);
    assertThat(accountKey2).isEqualTo(accountKey1);
    assertThat(integer("SELECT execution_generation FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + accountItem + "'", importId))
        .isEqualTo(1);

    MessageEnvelope lateFirstRetryResult = result(
        "CreateAccountResult", "authentication", importId, accountItem,
        accountMessage1, accountOperation1, 1, "SUCCEEDED", null);
    assertThat(manager.applyResult(lateFirstRetryResult)).isTrue();
    assertThat(value("SELECT status::text FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + accountItem + "'", importId))
        .isEqualTo("COMPLETED");
  }

  @Test
  void authorizationCommandsUseCanonicalNamesAndRoleResultCausesGrant() {
    UUID importId = process("APPLYING", 1, 1);
    UUID accountId = UUID.randomUUID();
    UUID roleItem = item(importId, "ASSIGN_STUDENT_ROLE", Map.of("accountId", accountId, "roleCode", "ALUMNO"));
    UUID accessItem = item(importId, "GRANT_STUDENT_SYSTEM_ACCESS", Map.of(
        "systemAccessId", UUID.randomUUID(), "accountId", accountId, "systemCode", "LAVADO_ULTRASONICO"));

    manager.schedule();
    assertThat(value("SELECT message_type FROM outbox_messages WHERE message_kind='COMMAND' ORDER BY occurred_at LIMIT 1", null))
        .isEqualTo("AssignAccountRole");
    UUID roleMessage = uuidValue("SELECT current_command_message_id FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + roleItem + "'", importId);
    UUID roleOperation = uuidValue("SELECT current_operation_id FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + roleItem + "'", importId);
    MessageEnvelope roleResult = result(
        "AssignAccountRoleResult", "authorization", importId, roleItem,
        roleMessage, roleOperation, 1, "SUCCEEDED", null);
    assertThat(manager.applyResult(roleResult)).isTrue();

    manager.schedule();
    assertThat(value("SELECT message_type FROM outbox_messages WHERE message_kind='COMMAND' ORDER BY occurred_at DESC LIMIT 1", null))
        .isEqualTo("GrantSystemAccess");
    JsonNode grantPayload = mapper.readTree(jdbc.sql("""
        SELECT payload::text FROM outbox_messages WHERE message_type='GrantSystemAccess'
        """).query(String.class).single());
    JsonNode dependency = grantPayload.path("importExecution").path("causalDependencies").get(0);
    assertThat(dependency.path("priorCommandType").asString()).isEqualTo("AssignAccountRole");
    assertThat(dependency.path("priorResultMessageId").asString()).isEqualTo(roleResult.messageId().toString());
    assertThat(grantPayload.path("importExecution").path("scope").asString())
        .isEqualTo("GRANT_STUDENT_SYSTEM_ACCESS");
    assertThat(accessItem).isNotNull();
  }

  @Test
  void resumeAdvancesOneCommonGenerationAndIsFunctionallyIdempotent() {
    UUID importId = process("APPLICATION_FAILED", 1, 5);
    UUID failed = item(importId, "CREATE_PERSON", Map.of(
        "personId", UUID.randomUUID(), "institutionalEmail", "student@estoma.mx"));
    jdbc.sql("""
        UPDATE student_import_plan_items
        SET status='FAILED',execution_generation=1,last_result_status='REJECTED',
            received_result='{"status":"REJECTED"}'::jsonb,last_error_code='EMAIL_CONFLICT'
        WHERE import_id=:import AND plan_item_id=:item
        """).param("import", importId).param("item", failed).update();
    UUID admin = admin();
    UUID operation = UUID.randomUUID();
    String key = "resume:" + importId;

    MessageEnvelope first = command("ResumeStudentImport", importId, admin, operation, key);
    assertThat(commands.dispatch(first).status()).isEqualTo("SUCCEEDED");
    assertThat(integer("SELECT execution_generation FROM student_import_processes WHERE import_id=:id", importId))
        .isEqualTo(2);
    assertThat(integer("SELECT execution_generation FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + failed + "'", importId))
        .isEqualTo(2);
    assertThat(value("SELECT status::text FROM student_import_plan_items WHERE import_id=:id AND plan_item_id='" + failed + "'", importId))
        .isEqualTo("PENDING");
    assertThat(jdbc.sql("SELECT count(*) FROM student_import_resume_audit WHERE import_id=:id")
        .param("id", importId).query(Long.class).single()).isEqualTo(1);
    assertThat(types(importId)).contains("StudentImportApplicationResumed", "StudentImportProgressUpdated");

    MessageEnvelope replay = new MessageEnvelope(
        UUID.randomUUID(), first.messageType(), 1, "COMMAND", "bff", Instant.now(),
        first.correlationId(), null, operation, key, importId, 5,
        first.actor(), first.payload().deepCopy());
    assertThat(commands.dispatch(replay).status()).isEqualTo("SUCCEEDED");
    assertThat(integer("SELECT execution_generation FROM student_import_processes WHERE import_id=:id", importId))
        .isEqualTo(2);
    assertThat(jdbc.sql("SELECT count(*) FROM student_import_resume_audit WHERE import_id=:id")
        .param("id", importId).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void ambiguousFailureCannotCreateANewGeneration() {
    UUID importId = process("APPLICATION_FAILED", 3, 4);
    UUID failed = item(importId, "CREATE_PERSON", Map.of(
        "personId", UUID.randomUUID(), "institutionalEmail", "student@estoma.mx"));
    jdbc.sql("""
        UPDATE student_import_plan_items
        SET status='FAILED',execution_generation=3,last_result_status='FAILED',
            received_result='{"status":"FAILED"}'::jsonb,last_error_code='REMOTE_FAILURE'
        WHERE import_id=:import AND plan_item_id=:item
        """).param("import", importId).param("item", failed).update();
    UUID admin = admin();

    AcademicCommandDispatcher.Outcome outcome = commands.dispatch(
        command("ResumeStudentImport", importId, admin, UUID.randomUUID(), "resume-ambiguous:" + importId));
    assertThat(outcome.status()).isEqualTo("REJECTED");
    assertThat(outcome.errorCode()).isEqualTo("IMPORT_RESUME_NOT_ALLOWED");
    assertThat(integer("SELECT execution_generation FROM student_import_processes WHERE import_id=:id", importId))
        .isEqualTo(3);
  }

  @Test
  void staleValidatedPlanNeverStartsApplying() {
    UUID importId = process("VALIDATED", 0, 2);
    jdbc.sql("UPDATE academic_periods SET academic_data_status='PENDING_IMPORT' WHERE academic_period_id=:id")
        .param("id", period).update();
    UUID sectionId = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO course_sections (
          course_section_id,academic_period_id,nrc,name,status,version,created_at,updated_at)
        VALUES (:id,:period,'NRC-1','Clinic','ACTIVE',2,now(),now())
        """).param("id", sectionId).param("period", period).update();
    item(importId, "DEACTIVATE_COURSE_SECTION",
        Map.of("courseSectionId", sectionId, "academicPeriodId", period, "nrc", "NRC-1"),
        Map.of("courseSectionVersion", 1));

    manager.schedule();

    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId))
        .isEqualTo("VALIDATING");
    assertThat(value("SELECT academic_data_status::text FROM academic_periods WHERE academic_period_id=:id", period))
        .isEqualTo("PENDING_IMPORT");
    assertThat(jdbc.sql("SELECT count(*) FROM student_import_plan_items WHERE import_id=:id")
        .param("id", importId).query(Long.class).single()).isZero();
  }

  @Test
  void finalizationAtomicallyPublishesCompletionProgressAndPeriodReadiness() {
    UUID importId = process("APPLYING", 1, 7);
    item(importId, "FINALIZE_STUDENT_IMPORT", Map.of("academicPeriodId", period),
        Map.of("academicPeriodVersion", 1));

    manager.schedule();

    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId))
        .isEqualTo("COMPLETED");
    assertThat(value("SELECT academic_data_status::text FROM academic_periods WHERE academic_period_id=:id", period))
        .isEqualTo("READY");
    assertThat(types(importId)).contains("StudentImportCompleted", "StudentImportProgressUpdated");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type='AcademicPeriodDataStatusChanged' AND aggregate_id=:id")
        .param("id", period).query(Long.class).single()).isEqualTo(1);
  }

  private UUID process(String status, int generation, int version) {
    UUID id = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    jdbc.sql("""
        INSERT INTO student_import_processes (
          import_id,academic_period_id,academic_period_code,academic_period_version,import_mode,file_reference,
          file_checksum,status,started_by_account_id,authorized_by_account_id,process_version,execution_generation,
          started_at,application_started_at,created_at,updated_at)
        VALUES (:id,:period,'2026-1',1,'FULL',:ref,
          '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
          CAST(:status AS student_import_status),:actor,:actor,:version,:generation,
          now(),CASE WHEN :status='APPLYING' THEN now() ELSE NULL END,now(),now())
        """).param("id", id).param("period", period).param("ref", UUID.randomUUID().toString())
        .param("status", status).param("actor", actor).param("version", version).param("generation", generation).update();
    return id;
  }

  private UUID item(UUID importId, String type, Map<String, Object> effect) {
    return item(importId, type, effect, Map.of());
  }

  private UUID item(UUID importId, String type, Map<String, Object> effect, Map<String, Object> versions) {
    UUID id = UUID.randomUUID();
    int generation = integer("SELECT execution_generation FROM student_import_processes WHERE import_id=:id", importId);
    jdbc.sql("""
        INSERT INTO student_import_plan_items (
          import_id,plan_item_id,item_type,business_key,planned_effect,expected_versions,
          execution_generation,created_at,updated_at)
        VALUES (:import,:id,CAST(:type AS student_import_plan_item_type),'{}',CAST(:effect AS jsonb),
          CAST(:versions AS jsonb),:generation,now(),now())
        """).param("import", importId).param("id", id).param("type", type)
        .param("effect", mapper.writeValueAsString(effect)).param("versions", mapper.writeValueAsString(versions))
        .param("generation", generation).update();
    return id;
  }

  private UUID admin() {
    UUID account = UUID.randomUUID();
    UUID person = UUID.randomUUID();
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

  private MessageEnvelope command(String type, UUID importId, UUID actor, UUID operationId, String key) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, "COMMAND", "bff", Instant.now(), UUID.randomUUID(), null,
        operationId, key, importId, 1, new Actor("ACCOUNT", actor, null),
        mapper.createObjectNode().put("importId", importId.toString()).put("requestedByAccountId", actor.toString()));
  }

  private MessageEnvelope result(
      String type, String producer, UUID importId, UUID itemId, UUID causationId,
      UUID operationId, int generation, String status, String errorCode) {
    var data = mapper.createObjectNode()
        .put("importId", importId.toString())
        .put("planItemId", itemId.toString())
        .put("executionGeneration", generation);
    var payload = mapper.createObjectNode().put("status", status);
    payload.set("data", data);
    if (errorCode != null) payload.set("error", mapper.createObjectNode().put("code", errorCode));
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, "RESULT", producer, Instant.now(), importId, causationId,
        operationId, null, null, 1, null, payload);
  }

  private List<String> types(UUID importId) {
    return jdbc.sql("SELECT message_type FROM outbox_messages WHERE correlation_id=:id ORDER BY occurred_at,message_id")
        .param("id", importId).query(String.class).list();
  }

  private String value(String sql, UUID id) {
    JdbcClient.StatementSpec spec = jdbc.sql(sql);
    if (id != null) spec.param("id", id);
    return spec.query(String.class).single();
  }

  private int integer(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(Integer.class).single();
  }

  private UUID uuidValue(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(UUID.class).single();
  }
}

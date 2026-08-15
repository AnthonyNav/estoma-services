package com.estoma.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.academic.application.StudentImportPersonResolutionResults;
import com.estoma.academic.application.StudentImportPlanBuilder;
import com.estoma.academic.contract.MessageEnvelope;
import java.time.Instant;
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
class StudentImportIdentityContractIT {
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
  @Autowired StudentImportPlanBuilder planBuilder;
  @Autowired StudentImportPersonResolutionResults personResults;

  UUID period;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE outbox_messages,processed_messages,idempotency_records,student_import_validation_work,student_import_validation_rows,student_import_validation_errors,student_import_plan_item_rebases,student_import_plan_items,student_import_processes,student_course_sections,course_sections,student_academic_profiles,academic_periods,person_status_projection,account_status_projection,authorization_context_projection,system_access_context_projection CASCADE").update();
    period = UUID.randomUUID();
    jdbc.sql("INSERT INTO academic_periods (academic_period_id,code,name,start_date,end_date,status,academic_data_status,version,created_at,updated_at) VALUES (:id,'2026-1','Spring',current_date,current_date,'ACTIVE','PENDING_IMPORT',1,now(),now())")
        .param("id", period).update();
  }

  @Test
  void foundPersonWithSupervisorAccountStillGetsASeparateStudentAccountPlan() {
    UUID importId = importWithStudent("ST-1", "student@estoma.mx");
    UUID person = UUID.randomUUID();
    UUID supervisorAccount = UUID.randomUUID();
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:person,'ACTIVE',4,now())")
        .param("person", person).update();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:account,:person,'USERNAME','ACTIVE',7,now())")
        .param("account", supervisorAccount).param("person", person).update();
    resolution(importId, "student@estoma.mx", "{\"outcome\":\"FOUND\",\"personId\":\"" + person + "\",\"status\":\"ACTIVE\"}");

    assertThat(planBuilder.buildIfReady(importId)).isTrue();
    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId)).isEqualTo("VALIDATED");
    assertThat(jdbc.sql("SELECT count(*) FROM student_import_plan_items WHERE import_id=:id AND item_type='CREATE_ACCOUNT'")
        .param("id", importId).query(Long.class).single()).isEqualTo(1);
    String plannedAccount = jdbc.sql("SELECT planned_effect->>'accountId' FROM student_import_plan_items WHERE import_id=:id AND item_type='CREATE_ACCOUNT'")
        .param("id", importId).query(String.class).single();
    assertThat(plannedAccount).isNotEqualTo(supervisorAccount.toString());
    assertThat(jdbc.sql("SELECT planned_effect->>'personId' FROM student_import_plan_items WHERE import_id=:id AND item_type='CREATE_ACCOUNT'")
        .param("id", importId).query(String.class).single()).isEqualTo(person.toString());
  }

  @Test
  void contradictoryStudentNumberAndResolvedPersonRejectsFirstImportAndPeriod() {
    UUID importId = importWithStudent("ST-1", "student@estoma.mx");
    UUID profilePerson = UUID.randomUUID();
    UUID profileAccount = UUID.randomUUID();
    UUID resolvedPerson = UUID.randomUUID();
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:person,'ACTIVE',1,now())")
        .param("person", profilePerson).update();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:account,:person,'MATRICULA','ACTIVE',1,now())")
        .param("account", profileAccount).param("person", profilePerson).update();
    jdbc.sql("INSERT INTO student_academic_profiles (student_academic_profile_id,person_id,account_id,student_number,student_number_normalized,current_semester,academic_status,version,student_number_version,created_at,updated_at) VALUES (:id,:person,:account,'ST-1','ST-1',4,'ACTIVE',3,0,now(),now())")
        .param("id", UUID.randomUUID()).param("person", profilePerson).param("account", profileAccount).update();
    resolution(importId, "student@estoma.mx", "{\"outcome\":\"FOUND\",\"personId\":\"" + resolvedPerson + "\",\"status\":\"ACTIVE\"}");

    assertThat(planBuilder.buildIfReady(importId)).isFalse();
    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId)).isEqualTo("REJECTED");
    assertThat(value("SELECT academic_data_status::text FROM academic_periods WHERE academic_period_id=:id", period)).isEqualTo("IMPORT_FAILED");
    assertThat(value("SELECT error_code FROM student_import_validation_errors WHERE import_id=:id", importId)).isEqualTo("IDENTITY_CONFLICT");
    assertThat(jdbc.sql("SELECT message_type FROM outbox_messages WHERE message_type IN ('StudentImportRejected','StudentImportValidationErrorsAvailable','AcademicPeriodDataStatusChanged')")
        .query(String.class).list()).contains("StudentImportRejected", "StudentImportValidationErrorsAvailable", "AcademicPeriodDataStatusChanged");
  }

  @Test
  void inactiveMatchFromCanonicalUsersProducerRejectsAndFailsFirstPeriod() {
    UUID importId = importProcess();
    UUID queryMessageId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    jdbc.sql("INSERT INTO student_import_validation_work (import_id,work_id,work_type,work_key,status,current_message_id,current_operation_id,attempts,created_at,updated_at) VALUES (:id,:work,'AUTHORITATIVE_PERSON_RESOLUTION','student@estoma.mx','WAITING_RESULT',:message,:operation,1,now(),now())")
        .param("id", importId).param("work", UUID.randomUUID()).param("message", queryMessageId).param("operation", operationId).update();
    publishedResolutionQuery(importId, queryMessageId, operationId, "student@estoma.mx");
    UUID inactivePerson = UUID.randomUUID();

    assertThat(personResults.apply(result(importId, queryMessageId, operationId, "users", "INACTIVE_MATCH", inactivePerson, "INACTIVE"))).isTrue();
    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id", importId)).isEqualTo("REJECTED");
    assertThat(value("SELECT academic_data_status::text FROM academic_periods WHERE academic_period_id=:id", period)).isEqualTo("IMPORT_FAILED");
    assertThat(value("SELECT error_code FROM student_import_validation_errors WHERE import_id=:id", importId)).isEqualTo("IDENTITY_CONFLICT");
  }

  @Test
  void legacyUsersServiceProducerIsRejectedByTheResultContract() {
    UUID importId = importProcess();
    UUID queryMessageId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    assertThatThrownBy(() -> personResults.apply(result(
        importId, queryMessageId, operationId, "users-service", "NOT_FOUND", null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private UUID importWithStudent(String number, String email) {
    UUID importId = importProcess();
    jdbc.sql("INSERT INTO student_import_validation_work (import_id,work_id,work_type,work_key,status,created_at,updated_at) VALUES (:id,:work,'LOCAL_FILE_ACCESS','file','SUCCEEDED',now(),now())")
        .param("id", importId).param("work", UUID.randomUUID()).update();
    row(importId, number, email);
    return importId;
  }

  private UUID importProcess() {
    UUID importId = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.sql("INSERT INTO student_import_processes (import_id,academic_period_id,academic_period_code,academic_period_version,import_mode,file_reference,file_checksum,status,started_by_account_id,authorized_by_account_id,process_version,execution_generation,started_at,created_at,updated_at) VALUES (:id,:period,'2026-1',1,'FULL',:reference,'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef','VALIDATING',:actor,:actor,1,0,:now,:now,:now)")
        .param("id", importId).param("period", period).param("reference", UUID.randomUUID().toString())
        .param("actor", actor).param("now", java.sql.Timestamp.from(now)).update();
    return importId;
  }

  private void row(UUID importId, String number, String email) {
    jdbc.sql("INSERT INTO student_import_validation_rows (import_id,row_type,sequence,sheet_name,row_number,row_data,created_at) VALUES (:id,'STUDENT',1,'ALUMNOS',2,CAST(:data AS jsonb),now())")
        .param("id", importId)
        .param("data", mapper.writeValueAsString(Map.of(
            "studentNumber", number,
            "firstName", "Student",
            "paternalSurname", "Test",
            "maternalSurname", "",
            "institutionalEmail", email,
            "semester", 4)))
        .update();
  }

  private void resolution(UUID importId, String email, String result) {
    jdbc.sql("INSERT INTO student_import_validation_work (import_id,work_id,work_type,work_key,status,result,created_at,updated_at) VALUES (:id,:work,'AUTHORITATIVE_PERSON_RESOLUTION',:email,'SUCCEEDED',CAST(:result AS jsonb),now(),now())")
        .param("id", importId).param("work", UUID.randomUUID()).param("email", email).param("result", result).update();
  }

  private void publishedResolutionQuery(UUID importId, UUID messageId, UUID operationId, String email) {
    jdbc.sql("""
        INSERT INTO outbox_messages (
          message_id,message_type,message_version,message_kind,aggregate_id,aggregate_version,
          correlation_id,payload,metadata,status,attempts,occurred_at,available_at)
        VALUES (:message,'ResolvePersonByInstitutionalEmail',1,'QUERY',:import,1,
          :import,CAST(:payload AS jsonb),CAST(:metadata AS jsonb),'PENDING',0,now(),now())
        """).param("message", messageId).param("import", importId)
        .param("payload", mapper.writeValueAsString(Map.of("institutionalEmail", email)))
        .param("metadata", mapper.writeValueAsString(Map.of("operationId", operationId.toString())))
        .update();
  }

  private MessageEnvelope result(
      UUID importId,
      UUID queryMessageId,
      UUID operationId,
      String producer,
      String outcome,
      UUID personId,
      String status) {
    var data = mapper.createObjectNode().put("outcome", outcome);
    if (personId != null) data.put("personId", personId.toString());
    if (status != null) data.put("status", status);
    var payload = mapper.createObjectNode().put("status", "SUCCEEDED").set("data", data);
    return new MessageEnvelope(
        UUID.randomUUID(),
        "ResolvePersonByInstitutionalEmailResult",
        1,
        "RESULT",
        producer,
        Instant.now(),
        importId,
        queryMessageId,
        operationId,
        null,
        importId,
        1,
        null,
        payload);
  }

  private String value(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(String.class).single();
  }
}

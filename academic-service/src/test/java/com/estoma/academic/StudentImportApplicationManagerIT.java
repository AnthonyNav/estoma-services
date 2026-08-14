package com.estoma.academic;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.academic.application.AcademicCommandDispatcher;
import com.estoma.academic.application.StudentImportApplicationManager;
import com.estoma.academic.contract.Actor;
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

@SpringBootTest(properties = {"estoma.messaging.listeners-enabled=false", "estoma.messaging.topology-declare=false", "spring.task.scheduling.enabled=false"})
@Testcontainers @Tag("integration")
class StudentImportApplicationManagerIT {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("academic_db").withUsername("academic_service").withPassword("academic_service");
  static { POSTGRES.start(); }
  @DynamicPropertySource static void postgres(DynamicPropertyRegistry r) { r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername); r.add("spring.datasource.password", POSTGRES::getPassword); r.add("spring.flyway.url", POSTGRES::getJdbcUrl); r.add("spring.flyway.user", POSTGRES::getUsername); r.add("spring.flyway.password", POSTGRES::getPassword); }
  @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper; @Autowired StudentImportApplicationManager manager; @Autowired AcademicCommandDispatcher commands;
  UUID period;
  @BeforeEach void reset() { jdbc.sql("TRUNCATE TABLE outbox_messages,processed_messages,student_import_validation_work,student_import_validation_rows,student_import_validation_errors,student_import_plan_item_rebases,student_import_plan_items,student_import_processes,student_course_sections,course_sections,student_academic_profiles,academic_periods CASCADE").update(); period=UUID.randomUUID(); jdbc.sql("INSERT INTO academic_periods (academic_period_id,code,name,start_date,end_date,status,academic_data_status,version,created_at,updated_at) VALUES (:id,'2026-1','Spring',current_date,current_date,'ACTIVE','IMPORTING',1,now(),now())").param("id",period).update(); }

  @Test void locallyExecutesPlanAndAtomicallyCompletesImportAndPeriod() {
    UUID importId=process(); UUID section=UUID.randomUUID(); item(importId,"REGISTER_COURSE_SECTION",Map.of("courseSectionId",section,"academicPeriodId",period,"nrc","NRC-1","name","Clinic")); item(importId,"FINALIZE_STUDENT_IMPORT",Map.of("academicPeriodId",period), Map.of("academicPeriodVersion", 1));
    manager.runOne(importId); manager.runOne(importId); manager.runOne(importId);
    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id",importId)).isEqualTo("COMPLETED");
    assertThat(value("SELECT academic_data_status::text FROM academic_periods WHERE academic_period_id=:id",period)).isEqualTo("READY");
    assertThat(jdbc.sql("SELECT message_type FROM outbox_messages WHERE aggregate_id=:id").param("id",importId).query(String.class).list()).contains("StudentImportCompleted");
  }

  @Test void applicationStartsPersistedGenerationAndResumeDoesNotBlindlyAdvanceIt() {
    UUID importId=process(); UUID admin=UUID.randomUUID(); UUID person=UUID.randomUUID();
    jdbc.sql("UPDATE student_import_processes SET status='VALIDATED' WHERE import_id=:id").param("id",importId).update();
    jdbc.sql("UPDATE academic_periods SET academic_data_status='PENDING_IMPORT' WHERE academic_period_id=:id").param("id",period).update();
    UUID plan=item(importId,"CREATE_PERSON",Map.of("personId",UUID.randomUUID(),"institutionalEmail","student@estoma.mx"));
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:id,'ACTIVE',1,now())").param("id",person).update();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:id,:person,'ADMIN','ACTIVE',1,now())").param("id",admin).param("person",person).update();
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:id,'SUPERADMIN','ACTIVE',1,now())").param("id",admin).update();
    assertThat(commands.dispatch(command("ApplyStudentImport",importId,admin)).status()).isEqualTo("SUCCEEDED");
    assertThat(jdbc.sql("SELECT execution_generation FROM student_import_processes WHERE import_id=:id").param("id",importId).query(Integer.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT execution_generation FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",plan).query(Integer.class).single()).isEqualTo(1);
    assertThat(value("SELECT academic_data_status::text FROM academic_periods WHERE academic_period_id=:id",period)).isEqualTo("IMPORTING");
    jdbc.sql("UPDATE student_import_processes SET status='APPLICATION_FAILED' WHERE import_id=:id").param("id",importId).update();
    jdbc.sql("UPDATE student_import_plan_items SET status='FAILED' WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",plan).update();
    assertThat(commands.dispatch(command("ResumeStudentImport",importId,admin)).status()).isEqualTo("SUCCEEDED");
    assertThat(jdbc.sql("SELECT execution_generation FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",plan).query(Integer.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT message_type FROM outbox_messages WHERE aggregate_id=:id").param("id",importId).query(String.class).list()).contains("StudentImportApplicationStarted","StudentImportResumed");
  }

  @Test void publishesCanonicalRemoteCommandAndCorrelatesResultThroughInbox() {
    UUID importId=process(); UUID item=item(importId,"CREATE_PERSON",Map.of("personId",UUID.randomUUID(),"institutionalEmail","student@estoma.mx","firstName","Student")); manager.runOne(importId);
    String payload=jdbc.sql("SELECT payload::text FROM outbox_messages WHERE message_kind='COMMAND'").query(String.class).single();
    assertThat(mapper.readTree(payload).path("importExecution").path("importId").asString()).isEqualTo(importId.toString()); assertThat(mapper.readTree(payload).path("importExecution").path("causalDependencies").isArray()).isTrue();
    UUID message=jdbc.sql("SELECT current_command_message_id FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",item).query(UUID.class).single();
    var data=mapper.createObjectNode().put("importId",importId.toString()).put("planItemId",item.toString()).put("executionGeneration",0);
    var result=new MessageEnvelope(UUID.randomUUID(),"CreatePersonResult",1,"RESULT","users",Instant.now(),importId,message,UUID.randomUUID(),null,importId,1,null,mapper.createObjectNode().put("status","SUCCEEDED").set("data",data));
    assertThat(manager.applyResult(result)).isTrue(); assertThat(manager.applyResult(result)).isFalse();
    assertThat(value("SELECT status::text FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item",importId,item)).isEqualTo("COMPLETED");
  }

  @Test void persistsRemoteRejectionAsRecoverableApplicationFailure() {
    UUID importId=process(); UUID item=item(importId,"CREATE_PERSON",Map.of("personId",UUID.randomUUID(),"institutionalEmail","student@estoma.mx")); manager.runOne(importId);
    UUID message=jdbc.sql("SELECT current_command_message_id FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",item).query(UUID.class).single(); var data=mapper.createObjectNode().put("importId",importId.toString()).put("planItemId",item.toString()).put("executionGeneration",0);
    manager.applyResult(new MessageEnvelope(UUID.randomUUID(),"CreatePersonResult",1,"RESULT","users",Instant.now(),importId,message,UUID.randomUUID(),null,importId,1,null,mapper.createObjectNode().put("status","REJECTED").set("data",data).set("error",mapper.createObjectNode().put("code","EMAIL_CONFLICT"))));
    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id",importId)).isEqualTo("APPLICATION_FAILED"); assertThat(value("SELECT failure_code FROM student_import_processes WHERE import_id=:id",importId)).isEqualTo("EMAIL_CONFLICT"); assertThat(value("SELECT status::text FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item",importId,item)).isEqualTo("FAILED");
  }

  @Test void ignoresMissingOrMismatchedPlanResultsWithoutFailingLookup() {
    UUID importId=process();
    UUID item=item(importId,"CREATE_PERSON",Map.of("personId",UUID.randomUUID(),"institutionalEmail","student@estoma.mx"));
    manager.runOne(importId);
    UUID message=jdbc.sql("SELECT current_command_message_id FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",item).query(UUID.class).single();
    var data=mapper.createObjectNode().put("importId",importId.toString()).put("planItemId",UUID.randomUUID().toString()).put("executionGeneration",0);
    assertThat(manager.applyResult(result("users", importId, message, data))).isFalse();
    data.put("planItemId",item.toString());
    assertThat(manager.applyResult(result("authentication", importId, message, data))).isFalse();
    assertThat(value("SELECT status::text FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item",importId,item)).isEqualTo("PROCESSING");
  }

  @Test void normalizesCreatedStudentProfileNumberAndRejectsStaleLocalVersion() {
    UUID importId=process(); UUID profile=UUID.randomUUID();
    item(importId,"CREATE_STUDENT_PROFILE",Map.of("studentAcademicProfileId",profile,"personId",UUID.randomUUID(),"accountId",UUID.randomUUID(),"studentNumber"," st-01 ","currentSemester",1));
    manager.runOne(importId);
    assertThat(value("SELECT student_number_normalized FROM student_academic_profiles WHERE student_academic_profile_id=:id",profile)).isEqualTo("ST-01");

    UUID stale=UUID.randomUUID();
    jdbc.sql("INSERT INTO course_sections (course_section_id,academic_period_id,nrc,name,status,version,created_at,updated_at) VALUES (:id,:period,'NRC-STALE','Stale','ACTIVE',2,now(),now())").param("id",stale).param("period",period).update();
    UUID staleItem=item(importId,"DEACTIVATE_COURSE_SECTION",Map.of("courseSectionId",stale),Map.of("courseSectionVersion",1));
    manager.runOne(importId);
    assertThat(value("SELECT status::text FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item",importId,staleItem)).isEqualTo("FAILED");
    assertThat(value("SELECT status::text FROM student_import_processes WHERE import_id=:id",importId)).isEqualTo("APPLICATION_FAILED");
  }
  @Test void republishesLateResultWithSameIdempotencyKeyAndGeneration() {
    UUID importId=process(); UUID item=item(importId,"CREATE_PERSON",Map.of("personId",UUID.randomUUID(),"institutionalEmail","student@estoma.mx")); manager.runOne(importId);
    String key=jdbc.sql("SELECT current_idempotency_key FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",item).query(String.class).single();
    assertThat(jdbc.sql("SELECT next_attempt_at >= first_published_at + interval '2 minutes' FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",item).query(Boolean.class).single()).isTrue();
    jdbc.sql("UPDATE student_import_plan_items SET next_attempt_at=now()-interval '1 second' WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",item).update();
    manager.runOne(importId); manager.runOne(importId);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind='COMMAND'").query(Long.class).single()).isEqualTo(2);
    assertThat(jdbc.sql("SELECT current_idempotency_key FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",item).query(String.class).single()).isEqualTo(key);
    assertThat(jdbc.sql("SELECT execution_generation FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",item).query(Integer.class).single()).isZero();
    assertThat(jdbc.sql("SELECT next_attempt_at > now() + interval '4 minutes' FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item").param("import",importId).param("item",item).query(Boolean.class).single()).isTrue();
  }
  private UUID process() { UUID id=UUID.randomUUID(), actor=UUID.randomUUID(); jdbc.sql("INSERT INTO student_import_processes (import_id,academic_period_id,academic_period_code,academic_period_version,import_mode,file_reference,file_checksum,status,started_by_account_id,authorized_by_account_id,process_version,execution_generation,started_at,created_at,updated_at) VALUES (:id,:period,'2026-1',1,'FULL',:ref,'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef','APPLYING',:actor,:actor,1,0,now(),now(),now())").param("id",id).param("period",period).param("ref",UUID.randomUUID().toString()).param("actor",actor).update(); return id; }
  private MessageEnvelope result(String producer, UUID importId, UUID causation, tools.jackson.databind.node.ObjectNode data) {
    return new MessageEnvelope(UUID.randomUUID(),"CreatePersonResult",1,"RESULT",producer,Instant.now(),importId,causation,UUID.randomUUID(),null,importId,1,null,mapper.createObjectNode().put("status","SUCCEEDED").set("data",data));
  }
  private MessageEnvelope command(String type, UUID importId, UUID actor) {
    return new MessageEnvelope(UUID.randomUUID(),type,1,"COMMAND","bff",Instant.now(),importId,null,UUID.randomUUID(),type + ":" + importId,importId,1,new Actor("ACCOUNT",actor,null),mapper.createObjectNode().put("importId",importId.toString()).put("requestedByAccountId",actor.toString()));
  }
  private UUID item(UUID importId,String type,Map<String,Object> effect) { return item(importId,type,effect,Map.of()); }
  private UUID item(UUID importId,String type,Map<String,Object> effect,Map<String,Object> versions) { UUID id=UUID.randomUUID(); jdbc.sql("INSERT INTO student_import_plan_items (import_id,plan_item_id,item_type,business_key,planned_effect,expected_versions,created_at,updated_at) VALUES (:import,:id,CAST(:type AS student_import_plan_item_type),'{}',CAST(:effect AS jsonb),CAST(:versions AS jsonb),now(),now())").param("import",importId).param("id",id).param("type",type).param("effect",mapper.writeValueAsString(effect)).param("versions",mapper.writeValueAsString(versions)).update(); return id; }
  private String value(String sql,UUID id) { return jdbc.sql(sql).param("id",id).query(String.class).single(); }
  private String value(String sql,UUID importId,UUID item) { return jdbc.sql(sql).param("import",importId).param("item",item).query(String.class).single(); }
}

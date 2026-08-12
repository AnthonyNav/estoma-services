package com.estoma.academic;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.academic.application.AcademicCommandDispatcher;
import com.estoma.academic.application.AcademicEligibilityPolicyRegistry;
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

@SpringBootTest(properties = {"estoma.messaging.listeners-enabled=false", "estoma.messaging.topology-declare=false", "spring.task.scheduling.enabled=false"})
@Testcontainers
@Tag("integration")
class AcademicEligibilityIT {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("academic_db").withUsername("academic_service").withPassword("academic_service");
  static { POSTGRES.start(); }
  @DynamicPropertySource static void postgres(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername); registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl); registry.add("spring.flyway.user", POSTGRES::getUsername); registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired JdbcClient jdbc;
  @Autowired AcademicCommandDispatcher dispatcher;
  @Autowired AcademicEligibilityPolicyRegistry policies;
  @Autowired ObjectMapper mapper;
  UUID admin = UUID.randomUUID(); UUID person = UUID.randomUUID(); UUID account = UUID.randomUUID(); UUID profile = UUID.randomUUID();

  @BeforeEach void reset() {
    jdbc.sql("TRUNCATE TABLE outbox_messages,processed_messages,idempotency_records,student_course_sections,course_sections,student_academic_eligibilities,student_number_change_history,student_academic_profiles,academic_periods,person_status_projection,account_status_projection,authorization_context_projection,system_access_context_projection CASCADE").update();
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:id,'SUPERADMIN','ACTIVE',1,now()),(:student,'ALUMNO','ACTIVE',1,now())").param("id",admin).param("student",account).update();
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:id,'ACTIVE',1,now())").param("id",person).update();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:id,:person,'MATRICULA','ACTIVE',1,now())").param("id",account).param("person",person).update();
    policies.activateForEvaluation(1);
  }

  @Test void successfulProfileMutationCommitsEligibilityAndItsOutboxRecordsTogether() {
    create(5);
    assertThat(eligible()).isFalse();
    assertThat(eligibilityVersion()).isEqualTo(1);
    assertThat(events("StudentAcademicEligibilityChanged")).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind='RESULT'").query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind='EVENT'").query(Long.class).single()).isEqualTo(2);
  }

  @Test void semesterThresholdAndStatusChangesAdvanceIndependentEligibilityVersion() {
    create(5);
    assertThat(dispatcher.dispatch(command("ChangeStudentSemester", mapper.createObjectNode().put("studentAcademicProfileId", profile.toString()).put("currentSemester", 6).put("expectedVersion", 1))).status()).isEqualTo("SUCCEEDED");
    assertThat(eligible()).isTrue(); assertThat(eligibilityVersion()).isEqualTo(2);
    assertThat(dispatcher.dispatch(command("DeactivateStudentAcademicProfile", mapper.createObjectNode().put("studentAcademicProfileId", profile.toString()).put("expectedVersion", 2))).status()).isEqualTo("SUCCEEDED");
    assertThat(eligible()).isFalse(); assertThat(eligibilityVersion()).isEqualTo(3);
    assertThat(events("StudentAcademicEligibilityChanged")).isEqualTo(3);
  }

  @Test void policyActivationReevaluatesOnlyTheNextCommandEvenWhenResultIsUnchanged() {
    create(6);
    policies.activateForEvaluation(new com.estoma.academic.domain.AcademicEligibilityPolicy() {
      public String systemCode() { return com.estoma.academic.domain.AcademicEligibilityPolicyV1.SYSTEM_CODE; }
      public int version() { return 2; }
      public boolean isEligible(com.estoma.academic.domain.StudentAcademicProfile profile) {
        return profile.status() == com.estoma.academic.domain.StudentAcademicProfile.Status.ACTIVE
            && profile.currentSemester() >= 6;
      }
    });
    assertThat(profileVersion()).isEqualTo(1); // Changing policy configuration does not mutate the profile.
    assertThat(dispatcher.dispatch(command("ChangeStudentSemester", mapper.createObjectNode()
        .put("studentAcademicProfileId", profile.toString()).put("currentSemester", 7).put("expectedVersion", 1))).status()).isEqualTo("SUCCEEDED");
    assertThat(eligible()).isTrue(); assertThat(eligibilityVersion()).isEqualTo(2);
    assertThat(profileVersion()).isEqualTo(2);
    assertThat(jdbc.sql("SELECT policy_version FROM student_academic_eligibilities WHERE student_academic_profile_id=:id").param("id",profile).query(Integer.class).single()).isEqualTo(2);
  }

  @Test void profileCreationRejectsAnAccountBoundToAnotherPerson() {
    UUID otherPerson = UUID.randomUUID();
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:id,'ACTIVE',1,now())")
        .param("id", otherPerson).update();
    UUID otherAccount = UUID.randomUUID();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:id,:person,'MATRICULA','ACTIVE',1,now())")
        .param("id", otherAccount).param("person", otherPerson).update();
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:id,'ALUMNO','ACTIVE',1,now())")
        .param("id", otherAccount).update();
    assertThat(dispatcher.dispatch(command("CreateStudentAcademicProfile", mapper.createObjectNode()
        .put("studentAcademicProfileId", UUID.randomUUID().toString()).put("personId", person.toString())
        .put("accountId", otherAccount.toString()).put("studentNumber", "ST-CONFLICT")
        .put("currentSemester", 5).put("academicStatus", "ACTIVE"))).errorCode()).isEqualTo("ACCOUNT_CONFLICT");
  }

  @Test void updateStudentAcademicProfileDoesNotPerformANoOpMutation() {
    create(5);
    long outboxBefore = jdbc.sql("SELECT count(*) FROM outbox_messages").query(Long.class).single();
    assertThat(dispatcher.dispatch(command("UpdateStudentAcademicProfile", mapper.createObjectNode()
        .put("studentAcademicProfileId", profile.toString()).put("expectedVersion", 1))).errorCode())
        .isEqualTo("INVALID_MESSAGE");
    assertThat(profileVersion()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages").query(Long.class).single()).isEqualTo(outboxBefore + 1);
  }

  @Test void normalizedStudentNumberConstraintViolationLeavesNoBusinessStateAndCommitsOnlyRejectedResult() {
    create(5);
    UUID secondProfile = UUID.randomUUID(), secondPerson = UUID.randomUUID(), secondAccount = UUID.randomUUID();
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:id,'ACTIVE',1,now())").param("id",secondPerson).update();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:id,:person,'MATRICULA','ACTIVE',1,now())").param("id",secondAccount).param("person",secondPerson).update();
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:id,'ALUMNO','ACTIVE',1,now())").param("id",secondAccount).update();
    MessageEnvelope duplicate = command("CreateStudentAcademicProfile", mapper.createObjectNode().put("studentAcademicProfileId", secondProfile.toString()).put("personId", secondPerson.toString()).put("accountId", secondAccount.toString()).put("studentNumber", "ST-01").put("currentSemester", 5).put("academicStatus", "ACTIVE"));

    assertThat(dispatcher.dispatch(duplicate).errorCode()).isEqualTo("STUDENT_NUMBER_ALREADY_EXISTS");
    assertThat(jdbc.sql("SELECT count(*) FROM student_academic_profiles").query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM student_academic_eligibilities WHERE student_academic_profile_id=:id").param("id",secondProfile).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE causation_id=:id AND message_kind='EVENT'").param("id",duplicate.messageId()).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE causation_id=:id AND message_kind='RESULT' AND payload->'error'->>'code'='STUDENT_NUMBER_ALREADY_EXISTS'").param("id",duplicate.messageId()).query(Long.class).single()).isEqualTo(1);
  }

  @Test void persistedEligibilityRemainsAuthoritativeAfterItsOutboxEventIsDeleted() {
    create(6);
    jdbc.sql("DELETE FROM outbox_messages WHERE message_type='StudentAcademicEligibilityChanged' AND aggregate_id=(SELECT eligibility_id FROM student_academic_eligibilities WHERE student_academic_profile_id=:id)").param("id",profile).update();

    assertThat(jdbc.sql("SELECT eligible FROM student_academic_eligibilities WHERE student_academic_profile_id=:id").param("id",profile).query(Boolean.class).single()).isTrue();
    assertThat(jdbc.sql("SELECT policy_version FROM student_academic_eligibilities WHERE student_academic_profile_id=:id").param("id",profile).query(Integer.class).single()).isEqualTo(1);
    assertThat(eligibilityVersion()).isEqualTo(1);
    assertThat(events("StudentAcademicEligibilityChanged")).isZero();
  }

  @Test void staleExpectedVersionDoesNotMutateOrAppendAnEvent() {
    create(5); long outboxBefore = jdbc.sql("SELECT count(*) FROM outbox_messages").query(Long.class).single();
    assertThat(dispatcher.dispatch(command("ChangeStudentSemester", mapper.createObjectNode().put("studentAcademicProfileId", profile.toString()).put("currentSemester", 6).put("expectedVersion", 99))).errorCode()).isEqualTo("VERSION_CONFLICT");
    assertThat(jdbc.sql("SELECT current_semester FROM student_academic_profiles WHERE student_academic_profile_id=:id").param("id",profile).query(Integer.class).single()).isEqualTo(5);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages").query(Long.class).single()).isEqualTo(outboxBefore + 1); // rejection result is durable; no domain event was appended.
  }

  private void create(int semester) {
    assertThat(dispatcher.dispatch(command("CreateStudentAcademicProfile", mapper.createObjectNode().put("studentAcademicProfileId", profile.toString()).put("personId", person.toString()).put("accountId", account.toString()).put("studentNumber", "  st-01 ").put("currentSemester", semester).put("academicStatus", "ACTIVE"))).status()).isEqualTo("SUCCEEDED");
  }
  private boolean eligible() { return jdbc.sql("SELECT eligible FROM student_academic_eligibilities WHERE student_academic_profile_id=:id").param("id",profile).query(Boolean.class).single(); }
  private int eligibilityVersion() { return jdbc.sql("SELECT eligibility_version FROM student_academic_eligibilities WHERE student_academic_profile_id=:id").param("id",profile).query(Integer.class).single(); }
  private int profileVersion() { return jdbc.sql("SELECT version FROM student_academic_profiles WHERE student_academic_profile_id=:id").param("id",profile).query(Integer.class).single(); }
  private long events(String type) { return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type=:type").param("type",type).query(Long.class).single(); }
  private MessageEnvelope command(String type, tools.jackson.databind.JsonNode payload) { return new MessageEnvelope(UUID.randomUUID(),type,1,"COMMAND","bff",Instant.now(),UUID.randomUUID(),null,UUID.randomUUID(),UUID.randomUUID().toString(),null,null,new Actor("ACCOUNT",admin,null),payload); }
}

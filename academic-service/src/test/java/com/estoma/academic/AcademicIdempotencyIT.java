package com.estoma.academic;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.academic.application.AcademicCommandDispatcher;
import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class AcademicIdempotencyIT {
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
  UUID studentPerson = UUID.randomUUID();
  UUID studentAccount = UUID.randomUUID();
  UUID profile = UUID.randomUUID();

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE outbox_messages,processed_messages,idempotency_records,student_course_sections,course_sections,student_academic_eligibilities,student_number_change_history,student_academic_profiles,academic_periods,person_status_projection,account_status_projection,authorization_context_projection,system_access_context_projection CASCADE").update();
    jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:adminPerson,'ACTIVE',1,now()),(:studentPerson,'ACTIVE',1,now())")
        .param("adminPerson", adminPerson)
        .param("studentPerson", studentPerson)
        .update();
    jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:admin,:adminPerson,'USERNAME','ACTIVE',1,now()),(:student,:studentPerson,'MATRICULA','ACTIVE',1,now())")
        .param("admin", admin)
        .param("adminPerson", adminPerson)
        .param("student", studentAccount)
        .param("studentPerson", studentPerson)
        .update();
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:admin,'SUPERADMIN','ACTIVE',1,now()),(:student,'ALUMNO','ACTIVE',1,now())")
        .param("admin", admin)
        .param("student", studentAccount)
        .update();
  }

  @Test
  void sameFunctionalIntentionWithDifferentMessageIdsProducesOneEffectAndReusableResults() throws Exception {
    String idempotencyKey = UUID.randomUUID().toString();
    UUID firstMessageId = UUID.randomUUID();
    UUID secondMessageId = UUID.randomUUID();
    var payload = mapper.createObjectNode()
        .put("studentAcademicProfileId", profile.toString())
        .put("personId", studentPerson.toString())
        .put("accountId", studentAccount.toString())
        .put("studentNumber", "ST-CONCURRENT")
        .put("currentSemester", 6)
        .put("academicStatus", "ACTIVE");

    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> {
        start.await();
        return dispatcher.dispatch(command(firstMessageId, idempotencyKey, payload.deepCopy()));
      });
      var second = executor.submit(() -> {
        start.await();
        return dispatcher.dispatch(command(secondMessageId, idempotencyKey, payload.deepCopy()));
      });
      start.countDown();

      AcademicCommandDispatcher.Outcome firstOutcome = first.get(20, TimeUnit.SECONDS);
      AcademicCommandDispatcher.Outcome secondOutcome = second.get(20, TimeUnit.SECONDS);

      assertThat(firstOutcome.status()).isEqualTo("SUCCEEDED");
      assertThat(secondOutcome.status()).isEqualTo("SUCCEEDED");
      assertThat(firstOutcome.aggregateId()).isEqualTo(profile);
      assertThat(secondOutcome.aggregateId()).isEqualTo(profile);
      assertThat(firstOutcome.aggregateVersion()).isEqualTo(1);
      assertThat(secondOutcome.aggregateVersion()).isEqualTo(1);
    }

    assertThat(jdbc.sql("SELECT count(*) FROM student_academic_profiles WHERE student_academic_profile_id=:id")
        .param("id", profile)
        .query(Long.class)
        .single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM student_academic_eligibilities WHERE student_academic_profile_id=:id")
        .param("id", profile)
        .query(Long.class)
        .single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM idempotency_records WHERE producer='bff' AND idempotency_key=:key")
        .param("key", idempotencyKey)
        .query(Long.class)
        .single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM processed_messages WHERE consumer_name='academic.commands' AND message_id IN (:first,:second)")
        .param("first", firstMessageId)
        .param("second", secondMessageId)
        .query(Long.class)
        .single()).isEqualTo(2);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind='EVENT' AND causation_id IN (:first,:second)")
        .param("first", firstMessageId)
        .param("second", secondMessageId)
        .query(Long.class)
        .single()).isEqualTo(2);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind='RESULT' AND causation_id IN (:first,:second)")
        .param("first", firstMessageId)
        .param("second", secondMessageId)
        .query(Long.class)
        .single()).isEqualTo(2);
  }

  private MessageEnvelope command(
      UUID messageId,
      String idempotencyKey,
      tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(
        messageId,
        "CreateStudentAcademicProfile",
        1,
        "COMMAND",
        "bff",
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        idempotencyKey,
        null,
        null,
        new Actor("ACCOUNT", admin, null),
        payload);
  }
}

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

@SpringBootTest(properties = {"estoma.messaging.listeners-enabled=false", "estoma.messaging.topology-declare=false", "spring.task.scheduling.enabled=false"})
@Testcontainers
@Tag("integration")
class AcademicPeriodReadinessIT {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("academic_db").withUsername("academic_service").withPassword("academic_service");
  static { POSTGRES.start(); }
  @DynamicPropertySource static void postgres(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername); registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl); registry.add("spring.flyway.user", POSTGRES::getUsername); registry.add("spring.flyway.password", POSTGRES::getPassword);
  }
  @Autowired JdbcClient jdbc;
  @Autowired AcademicCommandDispatcher dispatcher;
  @Autowired ObjectMapper mapper;
  UUID admin = UUID.randomUUID(); UUID period = UUID.randomUUID();
  @BeforeEach void reset() {
    jdbc.sql("TRUNCATE TABLE outbox_messages,processed_messages,idempotency_records,student_course_sections,course_sections,student_academic_eligibilities,student_number_change_history,student_academic_profiles,academic_periods,person_status_projection,account_status_projection,authorization_context_projection CASCADE").update();
    jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:id,'SUPERADMIN','ACTIVE',1,now())").param("id",admin).update();
    jdbc.sql("INSERT INTO academic_periods (academic_period_id,code,name,start_date,end_date,status,academic_data_status,version,created_at,updated_at) VALUES (:id,'2026-1','Spring',current_date,current_date,'ACTIVE','PENDING_IMPORT',1,now(),now())").param("id",period).update();
  }
  @Test void pendingPeriodRejectsSectionWithAcademicPeriodNotReady() {
    assertThat(dispatcher.dispatch(command("RegisterCourseSection", mapper.createObjectNode().put("academicPeriodId", period.toString()).put("nrc","NRC-1").put("name","Clinical"))).errorCode()).isEqualTo("ACADEMIC_PERIOD_NOT_READY");
  }
  @Test void readyFixtureAllowsSectionRegistration() {
    jdbc.sql("UPDATE academic_periods SET academic_data_status='READY' WHERE academic_period_id=:id").param("id",period).update();
    assertThat(dispatcher.dispatch(command("RegisterCourseSection", mapper.createObjectNode().put("academicPeriodId", period.toString()).put("nrc","NRC-1").put("name","Clinical"))).status()).isEqualTo("SUCCEEDED");
  }
  @Test void closedPeriodRejectsBeforeReadiness() {
    jdbc.sql("UPDATE academic_periods SET status='CLOSED' WHERE academic_period_id=:id").param("id",period).update();
    assertThat(dispatcher.dispatch(command("RegisterCourseSection", mapper.createObjectNode().put("academicPeriodId", period.toString()).put("nrc","NRC-1").put("name","Clinical"))).errorCode()).isEqualTo("ACADEMIC_PERIOD_CLOSED");
  }
  private MessageEnvelope command(String type, tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(UUID.randomUUID(), type, 1, "COMMAND", "bff", Instant.now(), UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID().toString(), null, null, new Actor("ACCOUNT",admin,null), payload);
  }
}

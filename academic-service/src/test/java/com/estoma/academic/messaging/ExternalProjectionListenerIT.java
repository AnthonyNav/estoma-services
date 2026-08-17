package com.estoma.academic.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

@SpringBootTest(properties = {"estoma.messaging.listeners-enabled=true", "estoma.messaging.topology-declare=false", "spring.task.scheduling.enabled=false", "spring.rabbitmq.listener.simple.auto-startup=false"})
@Testcontainers
@Tag("integration")
class ExternalProjectionListenerIT {
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

  @Autowired ExternalProjectionListener listener;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper mapper;

  private final UUID account = UUID.randomUUID();
  private final UUID person = UUID.randomUUID();

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE processed_messages,account_status_projection,authorization_context_projection CASCADE").update();
  }

  @Test
  void accountLifecycleSnapshotPreservesLoginTypeWhenActivationOmitsIt() {
    listener.apply(event("AccountCreated", "authentication", 1,
        mapper.createObjectNode().put("accountId", account.toString()).put("personId", person.toString())
            .put("loginType", "MATRICULA").put("status", "INACTIVE")));
    listener.apply(event("AccountActivated", "authentication", 2,
        mapper.createObjectNode().put("accountId", account.toString()).put("personId", person.toString())
            .put("status", "ACTIVE")));

    assertThat(jdbc.sql("SELECT login_type FROM account_status_projection WHERE account_id=:id")
        .param("id", account).query(String.class).single()).isEqualTo("MATRICULA");
    assertThat(jdbc.sql("SELECT status FROM account_status_projection WHERE account_id=:id")
        .param("id", account).query(String.class).single()).isEqualTo("ACTIVE");
  }

  @Test
  void accountDeactivationReasonIsRetainedAndClearedAfterReactivation() {
    listener.apply(event("AccountCreated", "authentication", 1,
        mapper.createObjectNode().put("accountId", account.toString()).put("personId", person.toString())
            .put("loginType", "MATRICULA").put("status", "INACTIVE")));
    listener.apply(event("AccountActivated", "authentication", 2,
        mapper.createObjectNode().put("accountId", account.toString()).put("personId", person.toString())
            .put("status", "ACTIVE")));
    listener.apply(event("AccountDeactivated", "authentication", 3,
        mapper.createObjectNode().put("accountId", account.toString()).put("personId", person.toString())
            .put("status", "INACTIVE").put("reasonCode", "ACADEMIC_ABSENCE")));

    assertThat(jdbc.sql("SELECT last_deactivation_reason_code FROM account_status_projection WHERE account_id=:id")
        .param("id", account).query(String.class).single()).isEqualTo("ACADEMIC_ABSENCE");

    listener.apply(event("AccountActivated", "authentication", 4,
        mapper.createObjectNode().put("accountId", account.toString()).put("personId", person.toString())
            .put("status", "ACTIVE")));

    assertThat(jdbc.sql("SELECT last_deactivation_reason_code FROM account_status_projection WHERE account_id=:id")
        .param("id", account).query(String.class).optional()).isEmpty();
  }

  @Test
  void roleChangeDeactivatesPreviousRoleAndActivatesNewRole() {
    UUID studentRoleId = UUID.randomUUID();
    UUID adminRoleId = UUID.randomUUID();
    listener.apply(event("AccountRoleAssigned", "authorization", 1,
        mapper.createObjectNode().put("accountId", account.toString())
            .put("roleId", studentRoleId.toString()).put("roleCode", "ALUMNO")));
    listener.apply(event("AccountRoleChanged", "authorization", 2,
        mapper.createObjectNode().put("accountId", account.toString())
            .put("previousRoleId", studentRoleId.toString()).put("previousRoleCode", "ALUMNO")
            .put("newRoleId", adminRoleId.toString()).put("newRoleCode", "SUPERADMIN")));

    assertThat(jdbc.sql("SELECT status FROM authorization_context_projection WHERE account_id=:id AND role_code='ALUMNO'")
        .param("id", account).query(String.class).single()).isEqualTo("INACTIVE");
    assertThat(jdbc.sql("SELECT status FROM authorization_context_projection WHERE account_id=:id AND role_code='SUPERADMIN'")
        .param("id", account).query(String.class).single()).isEqualTo("ACTIVE");
  }

  @Test
  void malformedExternalPayloadIsRejectedBeforeInboxInsertion() {
    MessageEnvelope malformed = event("AccountActivated", "authentication", 1,
        mapper.createObjectNode().put("accountId", account.toString()));

    assertThatThrownBy(() -> listener.apply(malformed)).isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.sql("SELECT count(*) FROM processed_messages WHERE consumer_name='academic.events'")
        .query(Long.class).single()).isZero();
  }

  private MessageEnvelope event(String type, String producer, int version, tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(UUID.randomUUID(), type, 1, "EVENT", producer, Instant.now(),
        UUID.randomUUID(), null, null, null, account, version, null, payload);
  }
}

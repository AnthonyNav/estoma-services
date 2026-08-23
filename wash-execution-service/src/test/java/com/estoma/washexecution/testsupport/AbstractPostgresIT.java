package com.estoma.washexecution.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
public abstract class AbstractPostgresIT {
  @Container
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("wash_execution_db")
          .withUsername("wash_execution")
          .withPassword("wash_execution");

  @Autowired protected JdbcTemplate jdbc;

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("wash-execution.cleaning-worker-enabled", () -> "false");
  }

  @BeforeEach
  void clearDatabase() {
    jdbc.execute("""
        TRUNCATE TABLE
          resource_unavailability_affected_executions,
          cleaning_periods,
          resource_unavailabilities,
          resource_assignments,
          entry_validations,
          wash_executions,
          appointment_projection,
          appointment_time_slot_projection,
          administrative_closure_projection,
          resource_priority_projection,
          tank_projection,
          cabin_projection,
          authorization_access_projection,
          authorization_role_projection,
          projection_recovery_state,
          command_idempotency_records,
          processed_messages,
          outbox_messages
        CASCADE
        """);
  }
}

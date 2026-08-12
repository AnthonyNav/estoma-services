package com.estoma.washadministration.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@Tag("integration")
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("wash_administration_db")
          .withUsername("wash_administration_service")
          .withPassword("wash_administration_service")
          .withUrlParam("socketTimeout", "10")
          .withUrlParam("connectTimeout", "10");

  static {
    POSTGRES.start();
  }

  @Autowired private JdbcClient jdbc;

  @DynamicPropertySource
  static void registerPostgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("spring.flyway.placeholders.runtime_role", POSTGRES::getUsername);
  }

  @BeforeEach
  void clearDatabase() {
    jdbc.sql(
            "TRUNCATE TABLE outbox_messages, command_idempotency_records, processed_messages, "
                + "authorization_access_projection, authorization_role_projection, administrative_closures, "
                + "tanks, cabins, service_schedules, resource_priorities, service_calendars RESTART IDENTITY CASCADE")
        .update();
  }
}

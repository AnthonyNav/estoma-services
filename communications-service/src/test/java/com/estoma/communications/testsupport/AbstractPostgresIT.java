package com.estoma.communications.testsupport;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "spring.task.scheduling.enabled=false",
    "estoma.messaging.topology-declare=false",
    "estoma.messaging.listeners-enabled=false",
    "estoma.communications.email.worker.enabled=false",
    "estoma.communications.email.worker.poll-interval-ms=3600000",
    "estoma.communications.email.worker.sweeper-interval-ms=3600000"
})
@Tag("integration")
public abstract class AbstractPostgresIT {

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("communications_db")
          .withUsername("communications_service")
          .withPassword("communications_service")
          .withUrlParam("socketTimeout", "10")
          .withUrlParam("connectTimeout", "10");

  static { POSTGRES.start(); }

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
}

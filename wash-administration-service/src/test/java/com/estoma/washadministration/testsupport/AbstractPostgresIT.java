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
import org.testcontainers.rabbitmq.RabbitMQContainer;

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

  protected static final RabbitMQContainer RABBITMQ =
      new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  static {
    POSTGRES.start();
    RABBITMQ.start();
  }

  @Autowired protected JdbcClient jdbc;

  @DynamicPropertySource
  static void registerPostgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("spring.flyway.placeholders.runtime_role", POSTGRES::getUsername);
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
  }

  @BeforeEach
  void clearDatabase() {
    jdbc.sql(
            "TRUNCATE TABLE projection_recovery_jobs, administrative_audit_records, outbox_messages, command_idempotency_records, processed_messages, "
                + "authorization_access_projection, authorization_role_projection, administrative_closures, "
                + "tanks, cabins, service_schedules, resource_priorities, service_calendars RESTART IDENTITY CASCADE")
        .update();
    seedTrustedAuthorizationGeneration();
  }

  protected void seedTrustedAuthorizationGeneration() {
    jdbc.sql(
            """
            INSERT INTO projection_recovery_jobs (
              recovery_job_id, projection_type, scope, status,
              created_at, updated_at, completed_at
            ) VALUES (
              '00000000-0000-0000-0000-00000000f001'::uuid,
              'AUTHORIZATION', 'FULL', 'COMPLETED', now(), now(), now()
            )
            """)
        .update();
  }

  protected void clearAuthorizationRecoveryHistory() {
    jdbc.sql("DELETE FROM projection_recovery_jobs WHERE projection_type = 'AUTHORIZATION'").update();
  }
}

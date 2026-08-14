package com.estoma.authorization.testsupport;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
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

  private static final String MIGRATION_USER = "authorization_migration";
  private static final String MIGRATION_PASSWORD = "authorization_migration";
  private static final String RUNTIME_USER = "authorization_runtime";
  private static final String RUNTIME_PASSWORD = "authorization_runtime";

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("authorization_db")
          .withUsername(MIGRATION_USER)
          .withPassword(MIGRATION_PASSWORD)
          .withUrlParam("socketTimeout", "10")
          .withUrlParam("connectTimeout", "10");

  static {
    POSTGRES.start();
    createRuntimeRole();
  }

  @DynamicPropertySource
  static void registerPostgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> RUNTIME_USER);
    registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", () -> MIGRATION_USER);
    registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    registry.add("spring.flyway.placeholders.runtime_role", () -> RUNTIME_USER);
  }

  private static void createRuntimeRole() {
    try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD);
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE ROLE " + RUNTIME_USER + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
    } catch (SQLException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}

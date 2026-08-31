package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies that only obsolete internal expiration results are discarded during the upgrade. */
@Testcontainers
class FlywayV15ToV16UpgradeIT {

  @Test
  void discardsOnlyHistoricalInternalExpirationResults() throws Exception {
    try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("communications_upgrade")
        .withUsername("communications_service")
        .withPassword("communications_service")) {
      postgres.start();
      migrate(postgres, "14");

      UUID legacyInternal = insertOutbox(postgres, "ExpireInboxMessagesSucceeded", null, null);
      UUID recentInternal = insertOutbox(
          postgres,
          "ExpireInboxMessagesSucceeded",
          "{\"type\":\"SYSTEM\",\"service\":\"communications\",\"accountId\":null}",
          "communications");
      UUID externalResult = insertOutbox(
          postgres,
          "ExpireInboxMessagesSucceeded",
          "{\"type\":\"SYSTEM\",\"service\":\"communications\"}",
          "appointments");
      UUID unrelatedResult = insertOutbox(postgres, "CommunicationRequestedSucceeded", null, null);

      migrate(postgres, "16");

      UUID pendingRace = insertOutbox(
          postgres,
          "ExpireInboxMessagesSucceeded",
          "{\"type\":\"SYSTEM\",\"service\":\"communications\"}",
          "communications",
          "PENDING");
      UUID failedRace = insertOutbox(
          postgres,
          "ExpireInboxMessagesSucceeded",
          "{\"type\":\"SYSTEM\",\"service\":\"communications\"}",
          "communications",
          "FAILED");

      migrate(postgres, null);

      assertStatus(postgres, legacyInternal, "DISCARDED", "DISCARDED_INTERNAL_RESULT_NO_CONSUMER");
      assertStatus(postgres, recentInternal, "DISCARDED", "DISCARDED_INTERNAL_RESULT_NO_CONSUMER");
      assertStatus(postgres, externalResult, "FAILED", "RABBIT_PUBLICATION_NOT_CONFIRMED");
      assertStatus(postgres, unrelatedResult, "FAILED", "RABBIT_PUBLICATION_NOT_CONFIRMED");
      assertStatus(postgres, pendingRace, "DISCARDED", "DISCARDED_INTERNAL_RESULT_NO_CONSUMER");
      assertStatus(postgres, failedRace, "DISCARDED", "DISCARDED_INTERNAL_RESULT_NO_CONSUMER");
    }
  }

  @Test
  void grantsV18ProjectionTablesToTheDistinctRuntimeRole() throws Exception {
    try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("communications_runtime_privileges")
        .withUsername("communications_service")
        .withPassword("communications_service")) {
      postgres.start();
      createRuntimeRole(postgres, "communications_runtime", "runtime-password");
      migrate(postgres, null, "communications_runtime");

      UUID periodId = UUID.randomUUID();
      try (Connection runtime = DriverManager.getConnection(
              postgres.getJdbcUrl(), "communications_runtime", "runtime-password");
          var statement = runtime.createStatement()) {
        assertThat(statement.executeQuery("SELECT readiness FROM academic_period_projection_state WHERE state_key = 1").next())
            .isTrue();
        assertThat(statement.executeUpdate("""
            INSERT INTO academic_period_projections (
              academic_period_id, code, start_date, end_date, status, source_version, source_occurred_at, updated_at
            ) VALUES ('%s', '2030-B', DATE '2030-08-01', DATE '2030-12-31', 'ACTIVE', 1, now(), now())
            """.formatted(periodId))).isEqualTo(1);
        assertThat(statement.executeUpdate("""
            UPDATE academic_period_projection_state SET updated_at = now() WHERE state_key = 1
            """)).isEqualTo(1);
        assertThat(statement.executeUpdate("DELETE FROM academic_period_projections WHERE academic_period_id = '%s'".formatted(periodId)))
            .isEqualTo(1);
      }
    }
  }

  private static void migrate(PostgreSQLContainer postgres, String target) {
    migrate(postgres, target, postgres.getUsername());
  }

  private static void migrate(PostgreSQLContainer postgres, String target, String runtimeRole) {
    var configuration = Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .placeholders(java.util.Map.of("runtime_role", runtimeRole));
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private static void createRuntimeRole(PostgreSQLContainer postgres, String username, String password) throws Exception {
    try (Connection connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE ROLE " + username + " LOGIN PASSWORD '" + password + "'");
    }
  }

  private static UUID insertOutbox(PostgreSQLContainer postgres, String type, String actor, String destination)
      throws Exception {
    return insertOutbox(postgres, type, actor, destination, "FAILED");
  }

  private static UUID insertOutbox(
      PostgreSQLContainer postgres, String type, String actor, String destination, String status)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.prepareStatement("""
            INSERT INTO outbox_messages (
              message_id, message_type, message_version, message_kind, correlation_id, payload,
              status, attempts, occurred_at, available_at, last_error, actor, destination
            ) VALUES (?, ?, 1, 'RESULT', ?, '{}'::jsonb, CAST(? AS outbox_status), 10, now(), now(),
              'RABBIT_PUBLICATION_NOT_CONFIRMED', CAST(? AS jsonb), ?)
            """)) {
      statement.setObject(1, id);
      statement.setString(2, type);
      statement.setObject(3, UUID.randomUUID());
      statement.setString(4, status);
      statement.setString(5, actor);
      statement.setString(6, destination);
      statement.executeUpdate();
    }
    return id;
  }

  private static void assertStatus(PostgreSQLContainer postgres, UUID id, String expectedStatus, String expectedError)
      throws Exception {
    try (Connection connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var query = connection.prepareStatement("SELECT status::text, last_error FROM outbox_messages WHERE message_id = ?")) {
      query.setObject(1, id);
      try (var rows = query.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(expectedStatus);
        assertThat(rows.getString(2)).isEqualTo(expectedError);
      }
    }
  }
}

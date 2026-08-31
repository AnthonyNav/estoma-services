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

      migrate(postgres, null);

      assertStatus(postgres, legacyInternal, "DISCARDED", "DISCARDED_INTERNAL_RESULT_NO_CONSUMER");
      assertStatus(postgres, recentInternal, "DISCARDED", "DISCARDED_INTERNAL_RESULT_NO_CONSUMER");
      assertStatus(postgres, externalResult, "FAILED", "RABBIT_PUBLICATION_NOT_CONFIRMED");
      assertStatus(postgres, unrelatedResult, "FAILED", "RABBIT_PUBLICATION_NOT_CONFIRMED");
    }
  }

  private static void migrate(PostgreSQLContainer postgres, String target) {
    var configuration = Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .placeholders(java.util.Map.of("runtime_role", postgres.getUsername()));
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private static UUID insertOutbox(PostgreSQLContainer postgres, String type, String actor, String destination)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.prepareStatement("""
            INSERT INTO outbox_messages (
              message_id, message_type, message_version, message_kind, correlation_id, payload,
              status, attempts, occurred_at, available_at, last_error, actor, destination
            ) VALUES (?, ?, 1, 'RESULT', ?, '{}'::jsonb, 'FAILED', 10, now(), now(),
              'RABBIT_PUBLICATION_NOT_CONFIRMED', CAST(? AS jsonb), ?)
            """)) {
      statement.setObject(1, id);
      statement.setString(2, type);
      statement.setObject(3, UUID.randomUUID());
      statement.setString(4, actor);
      statement.setString(5, destination);
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

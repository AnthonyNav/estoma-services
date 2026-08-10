package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exercises the shipped upgrade path rather than testing V12 only on an empty schema. */
@Testcontainers
class FlywayV10ToV12UpgradeIT {
  @Test
  void upgradesLegacyRowsAndGrantsTheNewAuditTable() throws Exception {
    try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("communications_upgrade").withUsername("communications_service").withPassword("communications_service")) {
      postgres.start();
      Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
           .locations("classpath:db/migration").placeholders(java.util.Map.of("runtime_role", postgres.getUsername())).target("10").load().migrate();
    UUID historicalCommunication = UUID.randomUUID();
    UUID pendingFirstRetryCommunication = UUID.randomUUID();
    UUID pendingSecondRetryCommunication = UUID.randomUUID();
    UUID newCommunication = UUID.randomUUID();
    UUID sendingCommunication = UUID.randomUUID();
    UUID historicalDelivery = UUID.randomUUID();
    UUID pendingFirstRetryDelivery = UUID.randomUUID();
    UUID pendingSecondRetryDelivery = UUID.randomUUID();
    UUID newDelivery = UUID.randomUUID();
    UUID sendingDelivery = UUID.randomUUID();
      try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
           var statement = connection.createStatement()) {
        statement.execute("CREATE ROLE communications_runtime LOGIN PASSWORD 'runtime_password'");
        insertCommunication(statement, historicalCommunication, "historical");
        insertCommunication(statement, pendingFirstRetryCommunication, "pending-first-retry");
        insertCommunication(statement, pendingSecondRetryCommunication, "pending-second-retry");
        insertCommunication(statement, newCommunication, "new");
        insertCommunication(statement, sendingCommunication, "sending");
        statement.execute("INSERT INTO inbox_messages (inbox_message_id, communication_id, title, body, status, created_at) VALUES ('" + UUID.randomUUID() + "', '" + sendingCommunication + "', 'legacy', 'body', 'ACTIVE', now())");
        insertDelivery(statement, historicalDelivery, historicalCommunication, "FAILED", 5, true);
        insertDelivery(statement, pendingFirstRetryDelivery, pendingFirstRetryCommunication, "PENDING", 5, false);
        insertDelivery(statement, pendingSecondRetryDelivery, pendingSecondRetryCommunication, "PENDING", 6, false);
        insertDelivery(statement, newDelivery, newCommunication, "PENDING", 0, false);
        insertDelivery(statement, sendingDelivery, sendingCommunication, "SENDING", 2, false);
        insertAttempts(statement, historicalDelivery, 7);
        insertAttempts(statement, pendingFirstRetryDelivery, 5);
        insertAttempts(statement, pendingSecondRetryDelivery, 6);
        insertAttempts(statement, sendingDelivery, 2);
      }
      Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
          .locations("classpath:db/migration").placeholders(java.util.Map.of("runtime_role", "communications_runtime")).load().migrate();
      try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement("SELECT attempt_count, retry_cycle, cycle_attempt_count, delivered_at, failed_at FROM email_deliveries WHERE email_delivery_id = ?")) {
         query.setObject(1, historicalDelivery);
         try (var rows = query.executeQuery()) {
           assertThat(rows.next()).isTrue();
           assertThat(rows.getInt(1)).isEqualTo(7);
           assertThat(rows.getInt(2)).isEqualTo(3);
           assertThat(rows.getInt(3)).isEqualTo(1);
           assertThat(rows.getObject(4)).isNull();
           assertThat(rows.getObject(5)).isNotNull();
         }
       }
       try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var query = connection.prepareStatement("SELECT attempt_number, retry_cycle, cycle_attempt_number FROM email_delivery_attempts WHERE email_delivery_id = ? ORDER BY attempt_number")) {
         query.setObject(1, historicalDelivery);
         try (var rows = query.executeQuery()) {
           for (int attempt = 1; attempt <= 7; attempt++) {
             assertThat(rows.next()).isTrue();
             assertThat(rows.getInt(1)).isEqualTo(attempt);
             assertThat(rows.getInt(2)).isEqualTo(attempt <= 5 ? 1 : attempt - 4);
             assertThat(rows.getInt(3)).isEqualTo(attempt <= 5 ? attempt : 1);
           }
         }
         assertDeliveryCycle(connection, pendingFirstRetryDelivery, 5, 2, 0);
         assertDeliveryCycle(connection, pendingSecondRetryDelivery, 6, 3, 0);
         assertDeliveryCycle(connection, newDelivery, 0, 1, 0);
         try (var sending = connection.prepareStatement("SELECT status::text, last_error_code, claim_token, claimed_at, lease_until, worker_id FROM email_deliveries WHERE email_delivery_id = ?")) {
           sending.setObject(1, sendingDelivery);
           try (var rows = sending.executeQuery()) {
             assertThat(rows.next()).isTrue();
             assertThat(rows.getString(1)).isEqualTo("INDETERMINATE");
             assertThat(rows.getString(2)).isEqualTo("LEGACY_SENDING_OUTCOME_UNKNOWN");
             assertThat(rows.getObject(3)).isNull();
             assertThat(rows.getObject(4)).isNull();
             assertThat(rows.getObject(5)).isNull();
             assertThat(rows.getObject(6)).isNull();
           }
         }
         assertThatThrownBy(() -> connection.createStatement().execute("UPDATE email_deliveries SET delivered_at = now() WHERE email_delivery_id = '" + historicalDelivery + "'"))
             .isInstanceOf(java.sql.SQLException.class);
         assertThatThrownBy(() -> connection.createStatement().execute("UPDATE email_deliveries SET failed_at = NULL WHERE email_delivery_id = '" + historicalDelivery + "'"))
             .isInstanceOf(java.sql.SQLException.class);
         assertThatThrownBy(() -> connection.createStatement().execute("UPDATE email_deliveries SET status = 'ACCEPTED', accepted_at = NULL WHERE email_delivery_id = '" + newDelivery + "'"))
             .isInstanceOf(java.sql.SQLException.class);
         assertThatThrownBy(() -> connection.createStatement().execute("UPDATE email_deliveries SET failed_at = now() WHERE email_delivery_id = '" + newDelivery + "'"))
             .isInstanceOf(java.sql.SQLException.class);
         assertThatThrownBy(() -> connection.createStatement().execute("UPDATE email_deliveries SET cycle_attempt_count = 7 WHERE email_delivery_id = '" + newDelivery + "'"))
             .isInstanceOf(java.sql.SQLException.class);
        }
      try (var runtime = DriverManager.getConnection(postgres.getJdbcUrl(), "communications_runtime", "runtime_password");
           var statement = runtime.createStatement()) {
         statement.execute("INSERT INTO email_delivery_administrative_actions (action_id, command_message_id, email_delivery_id, action, actor, reason, created_at) VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', '" + historicalDelivery + "', 'TEST', '{}'::jsonb, 'grant check', now())");
      }
    }
  }

  private static void insertCommunication(java.sql.Statement statement, UUID communicationId, String sourceReference) throws Exception {
    statement.execute("INSERT INTO communications (communication_id, recipient_account_id, system_code, communication_type, priority, source_service, source_reference_type, source_reference_id, status, created_at) VALUES ('" + communicationId + "', '" + UUID.randomUUID() + "', 'system', 'type', 'NORMAL', 'legacy', 'legacy', '" + sourceReference + "', 'PENDING', now())");
  }

  private static void insertDelivery(java.sql.Statement statement, UUID deliveryId, UUID communicationId, String status,
      int attemptCount, boolean withDeliveredAt) throws Exception {
    statement.execute("INSERT INTO email_deliveries (email_delivery_id, communication_id, recipient_address, from_name, from_address, content_mode, subject, text_body, status, attempt_count, delivered_at, created_at, updated_at) VALUES ('" + deliveryId + "', '" + communicationId + "', 'legacy@example.test', 'Legacy', 'legacy@example.test', 'GENERIC', 'subject', 'body', '" + status + "', " + attemptCount + ", " + (withDeliveredAt ? "now()" : "NULL") + ", now(), now())");
  }

  private static void insertAttempts(java.sql.Statement statement, UUID deliveryId, int count) throws Exception {
    for (int attempt = 1; attempt <= count; attempt++) {
      statement.execute("INSERT INTO email_delivery_attempts (email_delivery_attempt_id, email_delivery_id, attempt_number, provider, delivery_mode, status, started_at, completed_at) VALUES ('" + UUID.randomUUID() + "', '" + deliveryId + "', " + attempt + ", 'legacy', 'GENERIC', 'FAILED', now(), now())");
    }
  }

  private static void assertDeliveryCycle(java.sql.Connection connection, UUID deliveryId, int attempts,
      int retryCycle, int cycleAttempts) throws Exception {
    try (var query = connection.prepareStatement("SELECT attempt_count, retry_cycle, cycle_attempt_count FROM email_deliveries WHERE email_delivery_id = ?")) {
      query.setObject(1, deliveryId);
      try (var rows = query.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(attempts);
        assertThat(rows.getInt(2)).isEqualTo(retryCycle);
        assertThat(rows.getInt(3)).isEqualTo(cycleAttempts);
      }
    }
  }
}

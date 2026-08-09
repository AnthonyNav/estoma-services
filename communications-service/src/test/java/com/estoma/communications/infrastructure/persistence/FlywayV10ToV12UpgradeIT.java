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
    UUID communication = UUID.randomUUID();
    UUID delivery = UUID.randomUUID();
      try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
           var statement = connection.createStatement()) {
        statement.execute("CREATE ROLE communications_runtime LOGIN PASSWORD 'runtime_password'");
        statement.execute("INSERT INTO communications (communication_id, recipient_account_id, system_code, communication_type, priority, source_service, source_reference_type, source_reference_id, status, created_at) VALUES ('" + communication + "', '" + UUID.randomUUID() + "', 'system', 'type', 'NORMAL', 'legacy', 'legacy', '1', 'PENDING', now())");
        statement.execute("INSERT INTO inbox_messages (inbox_message_id, communication_id, title, body, status, created_at) VALUES ('" + UUID.randomUUID() + "', '" + communication + "', 'legacy', 'body', 'ACTIVE', now())");
        statement.execute("INSERT INTO email_deliveries (email_delivery_id, communication_id, recipient_address, from_name, from_address, content_mode, subject, text_body, status, attempt_count, created_at, updated_at) VALUES ('" + delivery + "', '" + communication + "', 'legacy@example.test', 'Legacy', 'legacy@example.test', 'GENERIC', 'subject', 'body', 'SENDING', 2, now(), now())");
        statement.execute("INSERT INTO email_delivery_attempts (email_delivery_attempt_id, email_delivery_id, attempt_number, provider, delivery_mode, status, started_at, completed_at) VALUES ('" + UUID.randomUUID() + "', '" + delivery + "', 1, 'legacy', 'GENERIC', 'FAILED', now(), now())");
        statement.execute("INSERT INTO email_delivery_attempts (email_delivery_attempt_id, email_delivery_id, attempt_number, provider, delivery_mode, status, started_at, completed_at) VALUES ('" + UUID.randomUUID() + "', '" + delivery + "', 2, 'legacy', 'GENERIC', 'ACCEPTED', now(), now())");
      }
      Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
          .locations("classpath:db/migration").placeholders(java.util.Map.of("runtime_role", "communications_runtime")).load().migrate();
      try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var query = connection.prepareStatement("SELECT d.status::text, d.last_error_code, d.retry_cycle, d.cycle_attempt_count, c.correlation_id, c.requested_channels FROM communications c JOIN email_deliveries d ON d.communication_id = c.communication_id WHERE d.email_delivery_id = ?")) {
        query.setObject(1, delivery);
        try (var rows = query.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("INDETERMINATE");
          assertThat(rows.getString(2)).isEqualTo("LEGACY_SENDING_OUTCOME_UNKNOWN");
          assertThat(rows.getInt(3)).isEqualTo(1);
          assertThat(rows.getInt(4)).isEqualTo(2);
          assertThat(rows.getObject(5)).isNull();
          assertThat((Object[]) rows.getArray(6).getArray()).containsExactly("INBOX", "EMAIL");
        }
      }
      try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
           var query = connection.prepareStatement("SELECT attempt_number, retry_cycle, cycle_attempt_number, status::text, normalized_error_code, claim_token, completed_at FROM email_delivery_attempts WHERE email_delivery_id = ? ORDER BY attempt_number")) {
        query.setObject(1, delivery);
        try (var rows = query.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(1);
          assertThat(rows.getInt(2)).isEqualTo(1);
          assertThat(rows.getInt(3)).isEqualTo(1);
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(2);
          assertThat(rows.getInt(2)).isEqualTo(1);
          assertThat(rows.getInt(3)).isEqualTo(2);
            assertThat(rows.getString(4)).isEqualTo("ACCEPTED");
            assertThat(rows.getString(5)).isNull();
            assertThat(rows.getObject(6)).isNull();
            assertThat(rows.getObject(7)).isNotNull();
        }
        assertThatThrownBy(() -> connection.createStatement().execute("INSERT INTO email_delivery_attempts (email_delivery_attempt_id, email_delivery_id, attempt_number, retry_cycle, cycle_attempt_number, provider, delivery_mode, status, started_at) VALUES ('" + UUID.randomUUID() + "', '" + delivery + "', 2, 2, 1, 'legacy', 'GENERIC', 'READY_TO_SEND', now())"))
            .isInstanceOf(java.sql.SQLException.class);
         assertThatThrownBy(() -> connection.createStatement().execute("INSERT INTO email_delivery_attempts (email_delivery_attempt_id, email_delivery_id, attempt_number, retry_cycle, cycle_attempt_number, provider, delivery_mode, status, started_at) VALUES ('" + UUID.randomUUID() + "', '" + delivery + "', 3, 1, 2, 'legacy', 'GENERIC', 'READY_TO_SEND', now())"))
             .isInstanceOf(java.sql.SQLException.class);
         assertThatThrownBy(() -> connection.createStatement().execute("INSERT INTO email_delivery_attempts (email_delivery_attempt_id, email_delivery_id, attempt_number, retry_cycle, cycle_attempt_number, provider, delivery_mode, status, started_at, call_started_at) VALUES ('" + UUID.randomUUID() + "', '" + delivery + "', 3, 1, 3, 'legacy', 'GENERIC', 'CALL_STARTED', now(), now())"))
             .isInstanceOf(java.sql.SQLException.class);
         assertThatThrownBy(() -> connection.createStatement().execute("UPDATE email_deliveries SET claim_token = gen_random_uuid() WHERE email_delivery_id = '" + delivery + "'"))
             .isInstanceOf(java.sql.SQLException.class);
       }
      try (var runtime = DriverManager.getConnection(postgres.getJdbcUrl(), "communications_runtime", "runtime_password");
           var statement = runtime.createStatement()) {
        statement.execute("INSERT INTO email_delivery_administrative_actions (action_id, command_message_id, email_delivery_id, action, actor, reason, created_at) VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', '" + delivery + "', 'TEST', '{}'::jsonb, 'grant check', now())");
      }
    }
  }
}

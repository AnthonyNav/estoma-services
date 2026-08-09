package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.RecoveryCodeDeriver;
import com.estoma.authentication.domain.recovery.RecoveryCodeDerivationContext;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class V17RecoveryMigrationIT extends AbstractPostgresIT {

  @Autowired private DataSource dataSource;
  @Autowired private JdbcClient jdbc;
  @Autowired private RecoveryCodeDeriver codeDeriver;

  @Test
  void invalidatesLegacyActiveRowsAndPreservesTheHmacMarkerCode() {
    String schema = "recovery_migration_" + UUID.randomUUID().toString().replace("-", "");
    UUID accountId = UUID.randomUUID();
    UUID markerAccountId = UUID.randomUUID();
    UUID invalidatedRecoveryId = UUID.randomUUID();
    UUID markerRecoveryId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant expiresAt = createdAt.plusSeconds(1800);

    try {
      createSchema(schema);
      migrateTo(schema, "16");
      insertAccount(schema, accountId, createdAt);
      insertAccount(schema, markerAccountId, createdAt);
      insertRecovery(schema, invalidatedRecoveryId, accountId, "legacy-sha256", "legacy-hash", createdAt, expiresAt);
      insertRecovery(schema, markerRecoveryId, markerAccountId, "legacy-sha256", "v1-hmac", createdAt, expiresAt);

      migrateTo(schema, "17");

      RecoveryRow invalidated = findRecovery(schema, invalidatedRecoveryId);
      assertThat(invalidated.status()).isEqualTo("INVALIDATED");
      assertThat(invalidated.codeDerivationContext()).isEqualTo(RecoveryCodeDerivationContext.HMAC_SHA256_V1);
      assertThat(invalidated.tokenHash()).isNull();
      assertThat(invalidated.invalidatedAt()).isNotNull();

      RecoveryRow marker = findRecovery(schema, markerRecoveryId);
      assertThat(marker.status()).isEqualTo("ACTIVE");
      assertThat(marker.codeDerivationContext()).isEqualTo(RecoveryCodeDerivationContext.HMAC_SHA256_V1);
      assertThat(marker.tokenHash()).isNull();
      String code = codeDeriver.derive(markerRecoveryId, marker.codeDerivationContext());
      assertThat(codeDeriver.matches(markerRecoveryId, marker.codeDerivationContext(), code)).isTrue();
    } finally {
      jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
    }
  }

  private void createSchema(String schema) {
    jdbc.sql("CREATE SCHEMA " + schema).update();
  }

  private void migrateTo(String schema, String target) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .placeholders(Map.of("runtime_role", "authentication_service"))
        .target(target)
        .load()
        .migrate();
  }

  private void insertAccount(String schema, UUID accountId, Instant now) {
    jdbc.sql(
            "INSERT INTO " + schema + ".accounts "
                + "(account_id, person_id, login_type, login_identifier, login_identifier_normalized, created_at, updated_at) "
                + "VALUES (:accountId, :personId, CAST('MATRICULA' AS " + schema + ".login_type), :login, :login, :createdAt, :createdAt)")
        .param("accountId", accountId)
        .param("personId", UUID.randomUUID())
        .param("login", "migration-" + accountId)
        .param("createdAt", Timestamp.from(now))
        .update();
  }

  private void insertRecovery(
      String schema,
      UUID recoveryId,
      UUID accountId,
      String context,
      String tokenHash,
      Instant createdAt,
      Instant expiresAt) {
    jdbc.sql(
            "INSERT INTO " + schema + ".password_recovery_requests "
                + "(recovery_id, account_id, token_hash, created_at, expires_at, status, failed_attempts, code_derivation_context) "
                + "VALUES (:recoveryId, :accountId, :tokenHash, :createdAt, :expiresAt, 'ACTIVE', 0, :context)")
        .param("recoveryId", recoveryId)
        .param("accountId", accountId)
        .param("tokenHash", tokenHash)
        .param("createdAt", Timestamp.from(createdAt))
        .param("expiresAt", Timestamp.from(expiresAt))
        .param("context", context)
        .update();
  }

  private RecoveryRow findRecovery(String schema, UUID recoveryId) {
    return jdbc.sql(
            "SELECT status, code_derivation_context, token_hash, invalidated_at FROM "
                + schema + ".password_recovery_requests WHERE recovery_id = :recoveryId")
        .param("recoveryId", recoveryId)
        .query((rs, rowNum) -> new RecoveryRow(
            rs.getString("status"),
            rs.getString("code_derivation_context"),
            rs.getString("token_hash"),
            rs.getTimestamp("invalidated_at")))
        .single();
  }

  private record RecoveryRow(String status, String codeDerivationContext, String tokenHash, Timestamp invalidatedAt) {}
}

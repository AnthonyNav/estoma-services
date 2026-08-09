package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.credential.PasswordHash;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC implementation of {@link CredentialRepository}. */
@Repository
class JdbcCredentialRepository implements CredentialRepository {

  private static final String COLUMNS = "account_id, password_hash, hash_algorithm, hash_parameters, password_changed_at, "
      + "must_change_password, temporary_password_expires_at, temporary_password_consumed_at, credential_version";

  private final JdbcClient jdbc;

  JdbcCredentialRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Credential> findByAccountId(UUID accountId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM account_credentials WHERE account_id = :accountId")
        .param("accountId", accountId)
        .query(JdbcCredentialRepository::mapRow)
        .optional();
  }

  @Override
  public boolean existsByAccountId(UUID accountId) {
    Long count =
        jdbc.sql("SELECT COUNT(*) FROM account_credentials WHERE account_id = :accountId")
            .param("accountId", accountId)
            .query(Long.class)
            .single();
    return count > 0;
  }

  @Override
  public void insert(Credential credential) {
    jdbc.sql(
            """
            INSERT INTO account_credentials (account_id, password_hash, hash_algorithm, hash_parameters, password_changed_at,
              must_change_password, temporary_password_expires_at, temporary_password_consumed_at, credential_version)
            VALUES (:accountId, :passwordHash, :hashAlgorithm, CAST(:hashParameters AS jsonb), :passwordChangedAt,
              :mustChangePassword, :temporaryPasswordExpiresAt, :temporaryPasswordConsumedAt, :credentialVersion)
            """)
        .param("accountId", credential.accountId())
        .param("passwordHash", credential.hash().value())
        .param("hashAlgorithm", credential.hash().algorithm())
        .param("hashParameters", credential.hash().parametersJson())
        .param("passwordChangedAt", Timestamps.toTimestamp(credential.passwordChangedAt()))
        .param("mustChangePassword", credential.mustChangePassword())
        .param("temporaryPasswordExpiresAt", Timestamps.toTimestamp(credential.temporaryPasswordExpiresAt()))
        .param("temporaryPasswordConsumedAt", Timestamps.toTimestamp(credential.temporaryPasswordConsumedAt()))
        .param("credentialVersion", credential.credentialVersion())
        .update();
  }

  @Override
  public int update(Credential credential) {
    return jdbc.sql(
            """
            UPDATE account_credentials SET
              password_hash = :passwordHash,
              hash_algorithm = :hashAlgorithm,
              hash_parameters = CAST(:hashParameters AS jsonb),
              password_changed_at = :passwordChangedAt,
              must_change_password = :mustChangePassword,
              temporary_password_expires_at = :temporaryPasswordExpiresAt,
              temporary_password_consumed_at = :temporaryPasswordConsumedAt,
              credential_version = :nextCredentialVersion
            WHERE account_id = :accountId AND credential_version = :credentialVersion
            """)
        .param("passwordHash", credential.hash().value())
        .param("hashAlgorithm", credential.hash().algorithm())
        .param("hashParameters", credential.hash().parametersJson())
        .param("passwordChangedAt", Timestamps.toTimestamp(credential.passwordChangedAt()))
        .param("mustChangePassword", credential.mustChangePassword())
        .param("temporaryPasswordExpiresAt", Timestamps.toTimestamp(credential.temporaryPasswordExpiresAt()))
        .param("temporaryPasswordConsumedAt", Timestamps.toTimestamp(credential.temporaryPasswordConsumedAt()))
        .param("nextCredentialVersion", credential.credentialVersion())
        .param("credentialVersion", credential.credentialVersion() - 1)
        .param("accountId", credential.accountId())
        .update();
  }

  @Override
  public int consumeTemporaryIfValid(UUID accountId, int credentialVersion, Instant now) {
    return jdbc.sql("""
            UPDATE account_credentials SET temporary_password_consumed_at = :now
            WHERE account_id = :accountId AND credential_version = :credentialVersion
              AND must_change_password = true AND temporary_password_consumed_at IS NULL
              AND temporary_password_expires_at > :now
            """)
        .param("accountId", accountId)
        .param("credentialVersion", credentialVersion)
        .param("now", Timestamps.toTimestamp(now))
        .update();
  }

  private static Credential mapRow(ResultSet rs, int rowNum) throws SQLException {
    PasswordHash hash = new PasswordHash(rs.getString("password_hash"), rs.getString("hash_algorithm"), rs.getString("hash_parameters"));
    return Credential.reconstitute(rs.getObject("account_id", UUID.class), hash, Timestamps.toInstant(rs, "password_changed_at"),
        rs.getBoolean("must_change_password"), Timestamps.toInstant(rs, "temporary_password_expires_at"),
        Timestamps.toInstant(rs, "temporary_password_consumed_at"), rs.getInt("credential_version"));
  }
}

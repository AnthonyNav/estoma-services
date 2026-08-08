package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.credential.PasswordHash;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC implementation of {@link CredentialRepository}. No version column: this table is 0..1 with {@code accounts}. */
@Repository
class JdbcCredentialRepository implements CredentialRepository {

  private static final String COLUMNS = "account_id, password_hash, hash_algorithm, hash_parameters, password_changed_at";

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
            INSERT INTO account_credentials (account_id, password_hash, hash_algorithm, hash_parameters, password_changed_at)
            VALUES (:accountId, :passwordHash, :hashAlgorithm, CAST(:hashParameters AS jsonb), :passwordChangedAt)
            """)
        .param("accountId", credential.accountId())
        .param("passwordHash", credential.hash().value())
        .param("hashAlgorithm", credential.hash().algorithm())
        .param("hashParameters", credential.hash().parametersJson())
        .param("passwordChangedAt", Timestamps.toTimestamp(credential.passwordChangedAt()))
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
              password_changed_at = :passwordChangedAt
            WHERE account_id = :accountId
            """)
        .param("passwordHash", credential.hash().value())
        .param("hashAlgorithm", credential.hash().algorithm())
        .param("hashParameters", credential.hash().parametersJson())
        .param("passwordChangedAt", Timestamps.toTimestamp(credential.passwordChangedAt()))
        .param("accountId", credential.accountId())
        .update();
  }

  private static Credential mapRow(ResultSet rs, int rowNum) throws SQLException {
    PasswordHash hash = new PasswordHash(rs.getString("password_hash"), rs.getString("hash_algorithm"), rs.getString("hash_parameters"));
    return Credential.reconstitute(rs.getObject("account_id", UUID.class), hash, Timestamps.toInstant(rs, "password_changed_at"));
  }
}

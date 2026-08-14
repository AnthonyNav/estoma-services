package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountStatusProjectionRepository implements AccountStatusProjectionRepository {

  private final JdbcClient jdbc;

  JdbcAccountStatusProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AccountStatusProjection> findByAccountId(UUID accountId) {
    return jdbc.sql(
            "SELECT account_id, person_id, login_type, status, source_version, updated_at "
                + "FROM account_status_projection WHERE account_id = :accountId")
        .param("accountId", accountId)
        .query(AccountProjectionRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public int upsertIfNewer(AccountStatusProjection projection) {
    return jdbc.sql(
            """
            INSERT INTO account_status_projection (
              account_id, person_id, login_type, status, source_version, updated_at
            ) VALUES (
              :accountId, :personId, CAST(:loginType AS login_type), CAST(:status AS projected_status),
              :sourceVersion, :updatedAt
            )
            ON CONFLICT (account_id) DO UPDATE SET
              person_id = EXCLUDED.person_id,
              login_type = EXCLUDED.login_type,
              status = EXCLUDED.status,
              source_version = EXCLUDED.source_version,
              updated_at = EXCLUDED.updated_at
            WHERE account_status_projection.source_version < EXCLUDED.source_version
            """)
        .param("accountId", projection.accountId())
        .param("personId", projection.personId())
        .param("loginType", projection.loginType().name())
        .param("status", projection.status().name())
        .param("sourceVersion", projection.sourceVersion())
        .param("updatedAt", Timestamps.toTimestamp(projection.updatedAt()))
        .update();
  }
}

package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.AuthorizationAccessProjectionRepository;
import com.estoma.washadministration.application.port.AuthorizationRoleProjectionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAuthorizationRoleProjectionRepository implements AuthorizationRoleProjectionRepository {

  private final JdbcClient jdbc;

  JdbcAuthorizationRoleProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RoleProjection> findByAccountId(UUID accountId) {
    return jdbc.sql(
            "SELECT account_id, role_code, source_version, updated_at FROM authorization_role_projection WHERE account_id = :accountId")
        .param("accountId", accountId)
        .query((rs, rowNum) ->
            new RoleProjection(
                rs.getObject("account_id", UUID.class),
                rs.getString("role_code"),
                rs.getInt("source_version"),
                Timestamps.toInstant(rs, "updated_at")))
        .optional();
  }

  @Override
  public int upsert(UUID accountId, String roleCode, int sourceVersion, Instant updatedAt) {
    return jdbc.sql(
            """
            INSERT INTO authorization_role_projection (account_id, role_code, source_version, updated_at)
            VALUES (:accountId, :roleCode, :sourceVersion, :updatedAt)
            ON CONFLICT (account_id) DO UPDATE SET
              role_code = EXCLUDED.role_code,
              source_version = EXCLUDED.source_version,
              updated_at = EXCLUDED.updated_at
            WHERE authorization_role_projection.source_version < EXCLUDED.source_version
            """)
        .param("accountId", accountId)
        .param("roleCode", roleCode)
        .param("sourceVersion", sourceVersion)
        .param("updatedAt", Timestamps.toTimestamp(updatedAt))
        .update();
  }

  @Override
  public void lockProjection() {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('wash-administration.authorization', 0))")
        .query((rs, rowNum) -> 1)
        .list();
  }
}

@Repository
class JdbcAuthorizationAccessProjectionRepository implements AuthorizationAccessProjectionRepository {

  private final JdbcClient jdbc;

  JdbcAuthorizationAccessProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AccessProjection> findByAccountId(UUID accountId, String systemCode) {
    return jdbc.sql(
            "SELECT account_id, system_code, access_status, source_version, updated_at "
                + "FROM authorization_access_projection WHERE account_id = :accountId AND system_code = :systemCode")
        .param("accountId", accountId)
        .param("systemCode", systemCode)
        .query((rs, rowNum) ->
            new AccessProjection(
                rs.getObject("account_id", UUID.class),
                rs.getString("system_code"),
                rs.getString("access_status"),
                rs.getInt("source_version"),
                Timestamps.toInstant(rs, "updated_at")))
        .optional();
  }

  @Override
  public int upsert(UUID accountId, String systemCode, String accessStatus, int sourceVersion, Instant updatedAt) {
    return jdbc.sql(
            """
            INSERT INTO authorization_access_projection
                (account_id, system_code, access_status, source_version, updated_at)
            VALUES (:accountId, :systemCode, CAST(:accessStatus AS authorization_access_status), :sourceVersion, :updatedAt)
            ON CONFLICT (account_id, system_code) DO UPDATE SET
              access_status = EXCLUDED.access_status,
              source_version = EXCLUDED.source_version,
              updated_at = EXCLUDED.updated_at
            WHERE authorization_access_projection.source_version < EXCLUDED.source_version
            """)
        .param("accountId", accountId)
        .param("systemCode", systemCode)
        .param("accessStatus", accessStatus)
        .param("sourceVersion", sourceVersion)
        .param("updatedAt", Timestamps.toTimestamp(updatedAt))
        .update();
  }
}

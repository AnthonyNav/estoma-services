package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.ProjectionRepositories;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthorizationProjectionRepository implements ProjectionRepositories.AuthorizationProjectionRepository {
  private final JdbcTemplate jdbc;

  public JdbcAuthorizationProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public Optional<ProjectionRepositories.RoleProjection> role(UUID accountId) {
    return jdbc.query("SELECT * FROM authorization_role_projection WHERE account_id=?", this::mapRole, accountId).stream().findFirst();
  }

  @Override
  public Optional<ProjectionRepositories.AccessProjection> access(UUID accountId, String systemCode) {
    return jdbc.query("SELECT * FROM authorization_access_projection WHERE account_id=? AND system_code=?",
        this::mapAccess, accountId, systemCode).stream().findFirst();
  }

  @Override
  public void upsertRole(ProjectionRepositories.RoleProjection role) {
    jdbc.update("""
        INSERT INTO authorization_role_projection(account_id,role_code,role_source_version,updated_at)
        VALUES (?,?,?,?)
        ON CONFLICT(account_id) DO UPDATE SET role_code=excluded.role_code,
          role_source_version=excluded.role_source_version,updated_at=excluded.updated_at
        WHERE authorization_role_projection.role_source_version < excluded.role_source_version
        """, role.accountId(), role.roleCode(), role.roleSourceVersion(), JdbcTemporal.timestamp(Instant.now()));
  }

  @Override
  public void upsertAccess(ProjectionRepositories.AccessProjection access) {
    jdbc.update("""
        INSERT INTO authorization_access_projection(
          account_id,system_id,system_code,access_status,access_source_version,updated_at)
        VALUES (?,?,?,?,?,?)
        ON CONFLICT(account_id,system_code) DO UPDATE SET system_id=excluded.system_id,
          access_status=excluded.access_status,access_source_version=excluded.access_source_version,
          updated_at=excluded.updated_at
        WHERE authorization_access_projection.access_source_version < excluded.access_source_version
        """, access.accountId(), access.systemId(), access.systemCode(), access.accessStatus(),
        access.accessSourceVersion(), JdbcTemporal.timestamp(Instant.now()));
  }

  private ProjectionRepositories.RoleProjection mapRole(ResultSet rs, int row) throws SQLException {
    return new ProjectionRepositories.RoleProjection(
        rs.getObject("account_id", UUID.class), rs.getString("role_code"), rs.getLong("role_source_version"));
  }

  private ProjectionRepositories.AccessProjection mapAccess(ResultSet rs, int row) throws SQLException {
    return new ProjectionRepositories.AccessProjection(
        rs.getObject("account_id", UUID.class), rs.getObject("system_id", UUID.class), rs.getString("system_code"),
        rs.getString("access_status"), rs.getLong("access_source_version"));
  }
}

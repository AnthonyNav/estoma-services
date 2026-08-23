package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthorizationProjectionRepository implements AuthorizationProjectionRepository {
  private final JdbcClient jdbc;
  public JdbcAuthorizationProjectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public void upsertAccountRole(AccountRoleProjection p) {
    jdbc.sql("""
      INSERT INTO account_role_projection
        (account_id,role_id,role_code,source_version,updated_at,reliable)
      VALUES (:account,:roleId,:roleCode,:version,:updated,:reliable)
      ON CONFLICT (account_id) DO UPDATE SET
        role_id=EXCLUDED.role_id,
        role_code=EXCLUDED.role_code,
        source_version=EXCLUDED.source_version,
        updated_at=EXCLUDED.updated_at,
        reliable=EXCLUDED.reliable
      WHERE account_role_projection.source_version < EXCLUDED.source_version
      """)
      .param("account", p.accountId())
      .param("roleId", p.roleId())
      .param("roleCode", p.roleCode())
      .param("version", p.sourceVersion())
      .param("updated", Timestamps.toTimestamp(p.updatedAt()))
      .param("reliable", p.reliable())
      .update();
  }

  @Override public void upsertSystemAccess(SystemAccessProjection p) {
    jdbc.sql("""
      INSERT INTO wash_system_access_projection
        (system_access_id,account_id,system_id,system_code,access_status,source_version,updated_at,reliable)
      VALUES (:id,:account,:systemId,:systemCode,:status,:version,:updated,:reliable)
      ON CONFLICT (system_access_id) DO UPDATE SET
        account_id=EXCLUDED.account_id,
        system_id=EXCLUDED.system_id,
        system_code=EXCLUDED.system_code,
        access_status=EXCLUDED.access_status,
        source_version=EXCLUDED.source_version,
        updated_at=EXCLUDED.updated_at,
        reliable=EXCLUDED.reliable
      WHERE wash_system_access_projection.source_version < EXCLUDED.source_version
      """)
      .param("id", p.systemAccessId())
      .param("account", p.accountId())
      .param("systemId", p.systemId())
      .param("systemCode", p.systemCode())
      .param("status", p.accessStatus())
      .param("version", p.sourceVersion())
      .param("updated", Timestamps.toTimestamp(p.updatedAt()))
      .param("reliable", p.reliable())
      .update();
  }

  @Override public Optional<AccountRoleProjection> findAccountRole(UUID accountId) {
    return jdbc.sql("SELECT account_id,role_id,role_code,source_version,updated_at,reliable FROM account_role_projection WHERE account_id=:account")
      .param("account", accountId)
      .query((rs,row) -> new AccountRoleProjection(
          rs.getObject("account_id", UUID.class), rs.getObject("role_id", UUID.class),
          rs.getString("role_code"), rs.getLong("source_version"),
          Timestamps.toInstant(rs,"updated_at"), rs.getBoolean("reliable")))
      .optional();
  }

  @Override public Optional<SystemAccessProjection> findSystemAccess(UUID accountId, String systemCode) {
    return jdbc.sql("SELECT system_access_id,account_id,system_id,system_code,access_status,source_version,updated_at,reliable FROM wash_system_access_projection WHERE account_id=:account AND system_code=:system")
      .param("account", accountId).param("system", systemCode)
      .query((rs,row) -> new SystemAccessProjection(
          rs.getObject("system_access_id", UUID.class), rs.getObject("account_id", UUID.class),
          rs.getObject("system_id", UUID.class), rs.getString("system_code"),
          rs.getString("access_status"), rs.getLong("source_version"),
          Timestamps.toInstant(rs,"updated_at"), rs.getBoolean("reliable")))
      .optional();
  }

  @Override public boolean hasAccess(UUID accountId, String roleCode, String systemCode) {
    return jdbc.sql("""
      SELECT count(*) FROM account_role_projection r
      JOIN wash_system_access_projection a ON a.account_id=r.account_id
      WHERE r.account_id=:account AND r.role_code=:role AND a.system_code=:system
        AND a.access_status='ACTIVE' AND r.reliable=true AND a.reliable=true
      """)
      .param("account",accountId).param("role",roleCode).param("system",systemCode)
      .query(Long.class).single()>0;
  }

  @Override public boolean reliable() {
    return jdbc.sql("SELECT COALESCE(bool_and(reliable),true) FROM (SELECT reliable FROM account_role_projection UNION ALL SELECT reliable FROM wash_system_access_projection) p")
      .query(Boolean.class).single();
  }
}

package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthorizationProjectionRepository implements AuthorizationProjectionRepository {
  private final JdbcClient jdbc;
  public JdbcAuthorizationProjectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override public void upsertAccountRole(UUID accountId, String role, long version) {
    jdbc.sql("INSERT INTO account_role_projection (source_id,account_id,role_code,source_version,updated_at) VALUES (:source,:account,:role,:version,now()) ON CONFLICT (source_id,role_code) DO UPDATE SET account_id=EXCLUDED.account_id,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at WHERE account_role_projection.source_version < EXCLUDED.source_version")
      .param("source",accountId).param("account",accountId).param("role",role).param("version",version).update();
  }
  @Override public void upsertSystemAccess(UUID accountId, String systemCode, boolean granted, long version) {
    jdbc.sql("INSERT INTO wash_system_access_projection (source_id,account_id,system_code,granted,source_version,updated_at) VALUES (:source,:account,:system,:granted,:version,now()) ON CONFLICT (source_id,system_code) DO UPDATE SET account_id=EXCLUDED.account_id,granted=EXCLUDED.granted,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at WHERE wash_system_access_projection.source_version < EXCLUDED.source_version")
      .param("source",accountId).param("account",accountId).param("system",systemCode).param("granted",granted).param("version",version).update();
  }
  @Override public boolean hasAccess(UUID accountId, String role, String systemCode) {
    return jdbc.sql("SELECT count(*) FROM account_role_projection r JOIN wash_system_access_projection a ON a.account_id=r.account_id WHERE r.account_id=:account AND r.role_code=:role AND a.system_code=:system AND a.granted=true AND r.reliable=true AND a.reliable=true")
      .param("account",accountId).param("role",role).param("system",systemCode).query(Long.class).single()>0;
  }
  @Override public boolean reliable() {
    return jdbc.sql("SELECT COALESCE(bool_and(reliable),true) FROM (SELECT reliable FROM account_role_projection UNION ALL SELECT reliable FROM wash_system_access_projection) p").query(Boolean.class).single();
  }
}

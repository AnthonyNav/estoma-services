package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.PermissionCatalogRepository;
import com.estoma.authorization.domain.model.PermissionCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPermissionCatalogRepository implements PermissionCatalogRepository {

  private static final String COLUMNS =
      "p.permission_id, p.system_id, p.code, p.name, p.status, p.created_at, p.updated_at";
  private final JdbcClient jdbc;

  JdbcPermissionCatalogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PermissionCatalog> findById(UUID permissionId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM permissions p WHERE p.permission_id = :permissionId")
        .param("permissionId", permissionId)
        .query(PermissionCatalogRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<PermissionCatalog> findBySystemIdAndCode(UUID systemId, String code) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM permissions p WHERE p.system_id = :systemId AND p.code = :code")
        .param("systemId", systemId)
        .param("code", code)
        .query(PermissionCatalogRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public List<PermissionCatalog> findByRoleId(UUID roleId) {
    return jdbc.sql(
            "SELECT " + COLUMNS
                + " FROM permissions p JOIN role_permissions rp ON rp.permission_id = p.permission_id "
                + "WHERE rp.role_id = :roleId ORDER BY p.code")
        .param("roleId", roleId)
        .query(PermissionCatalogRowMapper.INSTANCE)
        .list();
  }

  @Override
  public List<PermissionCatalog> findAll() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM permissions p ORDER BY p.code")
        .query(PermissionCatalogRowMapper.INSTANCE)
        .list();
  }
}

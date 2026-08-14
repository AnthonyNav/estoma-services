package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.RoleCatalogRepository;
import com.estoma.authorization.domain.model.RoleCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRoleCatalogRepository implements RoleCatalogRepository {

  private static final String COLUMNS = "role_id, code, name, status, created_at, updated_at";
  private final JdbcClient jdbc;

  JdbcRoleCatalogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RoleCatalog> findById(UUID roleId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM roles WHERE role_id = :roleId")
        .param("roleId", roleId)
        .query(RoleCatalogRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<RoleCatalog> findByCode(String code) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM roles WHERE code = :code")
        .param("code", code)
        .query(RoleCatalogRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public List<RoleCatalog> findAll() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM roles ORDER BY code")
        .query(RoleCatalogRowMapper.INSTANCE)
        .list();
  }
}

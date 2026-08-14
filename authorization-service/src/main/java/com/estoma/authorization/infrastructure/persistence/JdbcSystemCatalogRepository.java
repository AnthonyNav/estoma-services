package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.SystemCatalogRepository;
import com.estoma.authorization.domain.model.SystemCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSystemCatalogRepository implements SystemCatalogRepository {

  private static final String COLUMNS =
      "system_id, code, name, status, created_at, updated_at";
  private final JdbcClient jdbc;

  JdbcSystemCatalogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SystemCatalog> findById(UUID systemId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM systems WHERE system_id = :systemId")
        .param("systemId", systemId)
        .query(SystemCatalogRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<SystemCatalog> findByCode(String code) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM systems WHERE code = :code")
        .param("code", code)
        .query(SystemCatalogRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public List<SystemCatalog> findAll() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM systems ORDER BY code")
        .query(SystemCatalogRowMapper.INSTANCE)
        .list();
  }
}

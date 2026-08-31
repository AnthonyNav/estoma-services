package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.domain.model.CatalogStatus;
import com.estoma.authorization.domain.model.SystemCatalog;
import com.estoma.authorization.domain.model.SystemCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class SystemCatalogRowMapper implements RowMapper<SystemCatalog> {

  static final SystemCatalogRowMapper INSTANCE = new SystemCatalogRowMapper();

  private SystemCatalogRowMapper() {}

  @Override
  public SystemCatalog mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new SystemCatalog(
        rs.getObject("system_id", java.util.UUID.class),
        SystemCode.parse(rs.getString("code")),
        rs.getString("name"),
        CatalogStatus.valueOf(rs.getString("status")),
        rs.getInt("version"),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}

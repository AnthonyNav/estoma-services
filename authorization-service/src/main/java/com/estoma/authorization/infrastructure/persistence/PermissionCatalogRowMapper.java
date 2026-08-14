package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.domain.model.CatalogStatus;
import com.estoma.authorization.domain.model.PermissionCatalog;
import com.estoma.authorization.domain.model.PermissionCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class PermissionCatalogRowMapper implements RowMapper<PermissionCatalog> {

  static final PermissionCatalogRowMapper INSTANCE = new PermissionCatalogRowMapper();

  private PermissionCatalogRowMapper() {}

  @Override
  public PermissionCatalog mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new PermissionCatalog(
        rs.getObject("permission_id", java.util.UUID.class),
        rs.getObject("system_id", java.util.UUID.class),
        new PermissionCode(rs.getString("code")),
        rs.getString("name"),
        CatalogStatus.valueOf(rs.getString("status")),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}

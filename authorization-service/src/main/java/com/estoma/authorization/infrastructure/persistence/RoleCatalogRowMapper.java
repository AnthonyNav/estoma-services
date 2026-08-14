package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.domain.model.CatalogStatus;
import com.estoma.authorization.domain.model.RoleCatalog;
import com.estoma.authorization.domain.model.RoleCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class RoleCatalogRowMapper implements RowMapper<RoleCatalog> {

  static final RoleCatalogRowMapper INSTANCE = new RoleCatalogRowMapper();

  private RoleCatalogRowMapper() {}

  @Override
  public RoleCatalog mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new RoleCatalog(
        rs.getObject("role_id", java.util.UUID.class),
        RoleCode.parse(rs.getString("code")),
        rs.getString("name"),
        CatalogStatus.valueOf(rs.getString("status")),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}

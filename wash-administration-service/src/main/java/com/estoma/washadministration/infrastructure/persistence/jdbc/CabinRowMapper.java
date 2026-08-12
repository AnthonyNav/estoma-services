package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.domain.resource.Cabin;
import com.estoma.washadministration.domain.resource.CabinType;
import com.estoma.washadministration.domain.resource.ResourceStatus;
import com.estoma.washadministration.domain.shared.DisplayName;
import com.estoma.washadministration.domain.shared.ResourceCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

final class CabinRowMapper implements RowMapper<Cabin> {

  static final CabinRowMapper INSTANCE = new CabinRowMapper();

  private CabinRowMapper() {}

  @Override
  public Cabin mapRow(ResultSet rs, int rowNum) throws SQLException {
    return Cabin.reconstitute(
        rs.getObject("cabin_id", UUID.class),
        new ResourceCode(rs.getString("code")),
        new DisplayName(rs.getString("name")),
        CabinType.valueOf(rs.getString("cabin_type")),
        ResourceStatus.valueOf(rs.getString("status")),
        rs.getInt("version"),
        rs.getObject("created_by_account_id", UUID.class),
        rs.getObject("updated_by_account_id", UUID.class),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}

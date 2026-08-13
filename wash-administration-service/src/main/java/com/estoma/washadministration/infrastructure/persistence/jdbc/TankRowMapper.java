package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.domain.resource.ResourceStatus;
import com.estoma.washadministration.domain.resource.Tank;
import com.estoma.washadministration.domain.shared.DisplayName;
import com.estoma.washadministration.domain.shared.PositiveCapacity;
import com.estoma.washadministration.domain.shared.ResourceCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

final class TankRowMapper implements RowMapper<Tank> {

  static final TankRowMapper INSTANCE = new TankRowMapper();

  private TankRowMapper() {}

  @Override
  public Tank mapRow(ResultSet rs, int rowNum) throws SQLException {
    return Tank.reconstitute(
        rs.getObject("tank_id", UUID.class),
        rs.getObject("cabin_id", UUID.class),
        new ResourceCode(rs.getString("code")),
        new DisplayName(rs.getString("name")),
        new PositiveCapacity(rs.getInt("configured_capacity")),
        ResourceStatus.valueOf(rs.getString("status")),
        rs.getInt("version"),
        rs.getObject("created_by_account_id", UUID.class),
        rs.getObject("updated_by_account_id", UUID.class),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}

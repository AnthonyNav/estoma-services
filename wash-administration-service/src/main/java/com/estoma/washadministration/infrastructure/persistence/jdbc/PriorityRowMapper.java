package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.domain.priority.AppointmentType;
import com.estoma.washadministration.domain.priority.ResourcePriority;
import com.estoma.washadministration.domain.priority.UsageMode;
import com.estoma.washadministration.domain.resource.CabinType;
import com.estoma.washadministration.domain.resource.ResourceStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

final class PriorityRowMapper implements RowMapper<ResourcePriority> {

  static final PriorityRowMapper INSTANCE = new PriorityRowMapper();

  private PriorityRowMapper() {}

  @Override
  public ResourcePriority mapRow(ResultSet rs, int rowNum) throws SQLException {
    return ResourcePriority.reconstitute(
        rs.getObject("resource_priority_id", UUID.class),
        AppointmentType.valueOf(rs.getString("appointment_type")),
        CabinType.valueOf(rs.getString("cabin_type")),
        UsageMode.valueOf(rs.getString("usage_mode")),
        rs.getInt("priority_order"),
        ResourceStatus.valueOf(rs.getString("status")),
        rs.getInt("version"),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}

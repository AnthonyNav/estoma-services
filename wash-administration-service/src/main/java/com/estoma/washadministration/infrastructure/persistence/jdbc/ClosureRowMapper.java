package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.domain.closure.AdministrativeClosure;
import com.estoma.washadministration.domain.closure.AdministrativeClosureScope;
import com.estoma.washadministration.domain.closure.AdministrativeClosureStatus;
import com.estoma.washadministration.domain.shared.ClosureReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

final class ClosureRowMapper implements RowMapper<AdministrativeClosure> {

  static final ClosureRowMapper INSTANCE = new ClosureRowMapper();

  private ClosureRowMapper() {}

  @Override
  public AdministrativeClosure mapRow(ResultSet rs, int rowNum) throws SQLException {
    return AdministrativeClosure.reconstitute(
        rs.getObject("administrative_closure_id", UUID.class),
        rs.getObject("service_calendar_id", UUID.class),
        AdministrativeClosureScope.valueOf(rs.getString("scope")),
        rs.getObject("cabin_id", UUID.class),
        rs.getObject("tank_id", UUID.class),
        Timestamps.toInstant(rs, "starts_at"),
        Timestamps.toInstant(rs, "ends_at"),
        new ClosureReason(rs.getString("reason")),
        AdministrativeClosureStatus.valueOf(rs.getString("status")),
        rs.getInt("version"),
        rs.getObject("created_by_account_id", UUID.class),
        rs.getObject("cancelled_by_account_id", UUID.class),
        rs.getString("cancellation_reason") == null
            ? null
            : new ClosureReason(rs.getString("cancellation_reason")),
        Timestamps.toInstant(rs, "cancelled_at"),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}

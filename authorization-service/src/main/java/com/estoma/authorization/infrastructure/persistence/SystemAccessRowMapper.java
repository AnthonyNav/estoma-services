package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.SystemAccess;
import com.estoma.authorization.domain.model.SystemAccessReasonCode;
import com.estoma.authorization.domain.model.SystemAccessStatus;
import com.estoma.authorization.domain.model.SystemCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class SystemAccessRowMapper implements RowMapper<SystemAccess> {

  static final SystemAccessRowMapper INSTANCE = new SystemAccessRowMapper();

  private SystemAccessRowMapper() {}

  @Override
  public SystemAccess mapRow(ResultSet rs, int rowNum) throws SQLException {
    return SystemAccess.reconstitute(
        rs.getObject("system_access_id", java.util.UUID.class),
        rs.getObject("account_id", java.util.UUID.class),
        rs.getObject("system_id", java.util.UUID.class),
        SystemCode.parse(rs.getString("system_code")),
        SystemAccessStatus.valueOf(rs.getString("status")),
        nullableReason(rs.getString("reason_code")),
        rs.getString("reason_detail"),
        rs.getInt("version"),
        ActorJdbcCodec.read(rs, "granted_by_actor_type", "granted_by_account_id", "granted_by_actor_service"),
        ActorJdbcCodec.read(rs, "suspended_by_actor_type", "suspended_by_account_id", "suspended_by_actor_service"),
        ActorJdbcCodec.read(rs, "revoked_by_actor_type", "revoked_by_account_id", "revoked_by_actor_service"),
        ActorJdbcCodec.read(rs, "restored_by_actor_type", "restored_by_account_id", "restored_by_actor_service"),
        Timestamps.toInstant(rs, "granted_at"),
        Timestamps.toInstant(rs, "suspended_at"),
        Timestamps.toInstant(rs, "revoked_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }

  private static SystemAccessReasonCode nullableReason(String value) {
    return value == null ? null : SystemAccessReasonCode.valueOf(value);
  }
}

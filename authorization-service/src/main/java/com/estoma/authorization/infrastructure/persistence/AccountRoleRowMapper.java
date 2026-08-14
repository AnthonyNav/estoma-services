package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.domain.model.AccountRoleAssignment;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.RoleCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class AccountRoleRowMapper implements RowMapper<AccountRoleAssignment> {

  static final AccountRoleRowMapper INSTANCE = new AccountRoleRowMapper();

  private AccountRoleRowMapper() {}

  @Override
  public AccountRoleAssignment mapRow(ResultSet rs, int rowNum) throws SQLException {
    return AccountRoleAssignment.reconstitute(
        rs.getObject("account_id", java.util.UUID.class),
        rs.getObject("role_id", java.util.UUID.class),
        RoleCode.parse(rs.getString("role_code")),
        rs.getInt("version"),
        ActorJdbcCodec.read(rs, "assigned_by_actor_type", "assigned_by_account_id", "assigned_by_actor_service"),
        ActorJdbcCodec.read(rs, "changed_by_actor_type", "changed_by_account_id", "changed_by_actor_service"),
        Timestamps.toInstant(rs, "assigned_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}

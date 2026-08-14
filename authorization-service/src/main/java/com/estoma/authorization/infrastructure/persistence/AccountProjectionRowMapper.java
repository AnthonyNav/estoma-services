package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.AccountStatusProjectionRepository.AccountStatusProjection;
import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class AccountProjectionRowMapper implements RowMapper<AccountStatusProjection> {

  static final AccountProjectionRowMapper INSTANCE = new AccountProjectionRowMapper();

  private AccountProjectionRowMapper() {}

  @Override
  public AccountStatusProjection mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new AccountStatusProjection(
        rs.getObject("account_id", java.util.UUID.class),
        rs.getObject("person_id", java.util.UUID.class),
        LoginType.valueOf(rs.getString("login_type")),
        ProjectedStatus.valueOf(rs.getString("status")),
        rs.getInt("source_version"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}

package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.contracts.AccountStatus;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** Maps one row of {@code accounts} back into an {@link Account}. */
final class AccountRowMapper implements RowMapper<Account> {

  static final AccountRowMapper INSTANCE = new AccountRowMapper();

  private AccountRowMapper() {}

  @Override
  public Account mapRow(ResultSet rs, int rowNum) throws SQLException {
    LoginType loginType = LoginType.valueOf(rs.getString("login_type"));
    LoginIdentifier loginIdentifier =
        new LoginIdentifier(loginType, rs.getString("login_identifier"), rs.getString("login_identifier_normalized"));
    return Account.reconstitute(
        rs.getObject("account_id", UUID.class),
        rs.getObject("person_id", UUID.class),
        loginIdentifier,
        AccountStatus.valueOf(rs.getString("status")),
        rs.getInt("failed_attempts"),
        Timestamps.toInstant(rs, "locked_until"),
        rs.getInt("version"),
        Timestamps.toInstant(rs, "created_at"),
        Timestamps.toInstant(rs, "updated_at"),
        Timestamps.toInstant(rs, "deactivated_at"));
  }
}

package com.estoma.platformbff.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** PostgreSQL/JDBC time conversion seam. JDBC 4.2 does not define direct Instant binding. */
final class JdbcTimes {
  private JdbcTimes() {}

  static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  static Instant instant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}

package com.estoma.platformbff.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.SqlParameterValue;

/** PostgreSQL/JDBC time conversion seam for TIMESTAMPTZ columns. */
final class JdbcTimes {
  private JdbcTimes() {}

  static SqlParameterValue timestamp(Instant value) {
    return new SqlParameterValue(
        Types.TIMESTAMP_WITH_TIMEZONE,
        value == null ? null : value.atOffset(ZoneOffset.UTC));
  }

  static Instant instant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}

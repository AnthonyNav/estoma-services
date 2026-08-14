package com.estoma.authorization.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Converts domain instants to the JDBC representation used by PostgreSQL. */
public final class Timestamps {

  private Timestamps() {}

  public static OffsetDateTime toTimestamp(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  public static Instant toInstant(java.sql.ResultSet resultSet, String column)
      throws java.sql.SQLException {
    OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}

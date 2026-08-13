package com.estoma.washadministration.infrastructure.persistence.jdbc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class Timestamps {

  private Timestamps() {}

  static OffsetDateTime toTimestamp(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  static Instant toInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}

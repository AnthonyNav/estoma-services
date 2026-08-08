package com.estoma.communications.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The PostgreSQL JDBC driver cannot infer a SQL type for a bare {@link
 * Instant} parameter; {@link OffsetDateTime} maps to {@code timestamptz}
 * natively. Every repository in this package converts through here rather
 * than repeating the conversion inline.
 */
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

package com.estoma.appointments.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

final class Timestamps {
  private Timestamps() {}
  static Timestamp toTimestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
  static Instant toInstant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}

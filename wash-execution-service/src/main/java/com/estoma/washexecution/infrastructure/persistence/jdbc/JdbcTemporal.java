package com.estoma.washexecution.infrastructure.persistence.jdbc;

import java.sql.Timestamp;
import java.time.Instant;

public final class JdbcTemporal {
  private JdbcTemporal() {}

  public static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}

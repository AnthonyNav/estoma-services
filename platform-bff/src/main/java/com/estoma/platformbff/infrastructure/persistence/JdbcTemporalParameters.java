package com.estoma.platformbff.infrastructure.persistence;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.SqlParameterValue;

/** Centralizes PostgreSQL TIMESTAMPTZ bindings for JdbcClient. */
final class JdbcTemporalParameters {
  private JdbcTemporalParameters() {
  }

  static SqlParameterValue timestamptz(Instant value) {
    return new SqlParameterValue(
        Types.TIMESTAMP_WITH_TIMEZONE,
        value == null ? null : value.atOffset(ZoneOffset.UTC));
  }
}

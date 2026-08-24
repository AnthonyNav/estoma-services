package com.estoma.platformbff.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JdbcTimesTest {
  @Test
  void bindsInstantAsExplicitPostgresTimestamptz() {
    Instant instant = Instant.parse("2026-08-23T12:34:56Z");

    var parameter = JdbcTimes.timestamp(instant);

    assertThat(parameter.getSqlType()).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    assertThat(parameter.getValue()).isEqualTo(instant.atOffset(ZoneOffset.UTC));
  }

  @Test
  void bindsNullWithExplicitPostgresTimestamptzType() {
    var parameter = JdbcTimes.timestamp(null);

    assertThat(parameter.getSqlType()).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    assertThat(parameter.getValue()).isNull();
  }
}

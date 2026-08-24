package com.estoma.platformbff.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JdbcTemporalParametersTest {
  @Test
  void bindsInstantAsPostgresTimestamptz() {
    Instant instant = Instant.parse("2026-08-23T12:34:56Z");

    var parameter = JdbcTemporalParameters.timestamptz(instant);

    assertThat(parameter.getSqlType()).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    assertThat(parameter.getValue()).isEqualTo(instant.atOffset(ZoneOffset.UTC));
  }

  @Test
  void bindsNullWithExplicitTimestamptzType() {
    var parameter = JdbcTemporalParameters.timestamptz(null);

    assertThat(parameter.getSqlType()).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    assertThat(parameter.getValue()).isNull();
  }
}

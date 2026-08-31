package com.estoma.authorization.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class SchemaVersionHealthIndicatorTest {

  @Test
  void futureMigrationMakesSchemaNotReady() {
    Flyway flyway = mock(Flyway.class);
    MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
    MigrationInfo current = mock(MigrationInfo.class);
    MigrationInfo future = mock(MigrationInfo.class);

    when(flyway.info()).thenReturn(migrationInfo);
    when(migrationInfo.current()).thenReturn(current);
    when(migrationInfo.all()).thenReturn(new MigrationInfo[] {current, future});
    when(current.getState()).thenReturn(MigrationState.SUCCESS);
    when(current.getVersion()).thenReturn(MigrationVersion.fromVersion("1"));
    when(future.getState()).thenReturn(MigrationState.FUTURE_SUCCESS);

    var health = new SchemaVersionHealthIndicator(flyway).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("reason", "schema migration is not compatible");
  }

  @Test
  void cachesSuccessfulValidationInsteadOfQueryingFlywayOnEveryReadinessProbe() {
    Flyway flyway = mock(Flyway.class);
    MigrationInfoService migrationInfo = mock(MigrationInfoService.class);
    MigrationInfo current = mock(MigrationInfo.class);

    when(flyway.info()).thenReturn(migrationInfo);
    when(migrationInfo.current()).thenReturn(current);
    when(migrationInfo.all()).thenReturn(new MigrationInfo[] {current});
    when(current.getState()).thenReturn(MigrationState.SUCCESS);
    when(current.getVersion()).thenReturn(MigrationVersion.fromVersion("1"));

    var indicator = new SchemaVersionHealthIndicator(flyway);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    verify(flyway, times(1)).info();
    verify(flyway, times(1)).validate();
  }
}

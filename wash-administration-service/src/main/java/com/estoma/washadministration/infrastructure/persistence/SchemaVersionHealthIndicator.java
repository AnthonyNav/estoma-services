package com.estoma.washadministration.infrastructure.persistence;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/** Keeps startup/readiness down when the application and schema disagree. */
@Component
class SchemaVersionHealthIndicator implements HealthIndicator {

  private final Flyway flyway;
  private volatile Health successfulHealth;

  SchemaVersionHealthIndicator(Flyway flyway) {
    this.flyway = flyway;
  }

  @Override
  public Health health() {
    Health cached = successfulHealth;
    if (cached != null) {
      return cached;
    }
    synchronized (this) {
      if (successfulHealth != null) {
        return successfulHealth;
      }
      Health health = validateSchema();
      if (Status.UP.equals(health.getStatus())) {
        successfulHealth = health;
      }
      return health;
    }
  }

  private Health validateSchema() {
    MigrationInfoService migrationInfo = flyway.info();
    MigrationInfo current = migrationInfo.current();
    if (current == null) {
      return Health.down().withDetail("reason", "no migrations have been applied").build();
    }
    if (Arrays.stream(migrationInfo.all()).anyMatch(info -> info.getState().isFailed())) {
      return Health.down().withDetail("reason", "a migration is in a failed state").build();
    }
    if (migrationInfo.pending().length > 0) {
      return Health.down().withDetail("reason", "pending migrations have not been applied").build();
    }
    return Health.up().withDetail("version", current.getVersion().toString()).build();
  }
}

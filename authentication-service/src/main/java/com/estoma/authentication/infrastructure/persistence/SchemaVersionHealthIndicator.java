package com.estoma.authentication.infrastructure.persistence;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributes to the {@code startup} health group (system_contract.md
 * "Observability Parameters"): DOWN if the schema has a failed migration or
 * pending migrations not yet applied. A pod should not accept traffic --
 * or even be considered started -- against a schema it doesn't fully
 * understand.
 *
 * <p>Bean name {@code schemaVersionHealthIndicator} (the default for this
 * class) is what makes Spring register this contributor under the id
 * {@code schemaVersion} referenced in {@code application.yml}'s health
 * groups.
 */
@Component
class SchemaVersionHealthIndicator implements HealthIndicator {

  private final Flyway flyway;

  SchemaVersionHealthIndicator(Flyway flyway) {
    this.flyway = flyway;
  }

  @Override
  public Health health() {
    MigrationInfo current = flyway.info().current();
    if (current == null) {
      return Health.down().withDetail("reason", "no migrations have been applied").build();
    }
    boolean anyFailed = Arrays.stream(flyway.info().all()).anyMatch(info -> info.getState().isFailed());
    if (anyFailed) {
      return Health.down().withDetail("reason", "a migration is in a failed state").build();
    }
    if (flyway.info().pending().length > 0) {
      return Health.down().withDetail("reason", "pending migrations have not been applied").build();
    }
    return Health.up().withDetail("version", current.getVersion().toString()).build();
  }
}

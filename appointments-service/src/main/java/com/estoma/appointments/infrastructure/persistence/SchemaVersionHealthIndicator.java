package com.estoma.appointments.infrastructure.persistence;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness is DOWN for failed or unapplied schema migrations. */
@Component("schemaVersion")
@ConditionalOnBean(Flyway.class)
public class SchemaVersionHealthIndicator implements HealthIndicator {
  private final Flyway flyway;
  public SchemaVersionHealthIndicator(Flyway flyway) { this.flyway = flyway; }
  @Override public Health health() {
    MigrationInfo current = flyway.info().current();
    if (current == null) return Health.down().withDetail("reason", "no migrations have been applied").build();
    if (Arrays.stream(flyway.info().all()).anyMatch(i -> i.getState().isFailed())) return Health.down().withDetail("reason", "a migration is in a failed state").build();
    if (flyway.info().pending().length > 0) return Health.down().withDetail("reason", "pending migrations have not been applied").build();
    return Health.up().withDetail("version", current.getVersion().toString()).build();
  }
}

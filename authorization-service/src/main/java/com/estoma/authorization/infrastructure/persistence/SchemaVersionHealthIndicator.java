package com.estoma.authorization.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import java.util.Arrays;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.springframework.stereotype.Component;

/** Keeps schema compatibility visible to readiness without affecting liveness. */
@Component("schemaVersion")
public class SchemaVersionHealthIndicator implements HealthIndicator {

  private final Flyway flyway;

  public SchemaVersionHealthIndicator(Flyway flyway) {
    this.flyway = flyway;
  }

  @Override
  public Health health() {
    try {
      MigrationInfoService migrationInfo = flyway.info();
      MigrationInfo current = migrationInfo.current();
      if (current == null) {
        return Health.down().withDetail("reason", "no migrations have been applied").build();
      }
      flyway.validate();
      var incompatible =
          Arrays.stream(migrationInfo.all())
              .filter(info -> info.getState() != MigrationState.SUCCESS)
              .findFirst();
      if (incompatible.isPresent()) {
        return Health.down()
            .withDetail("reason", "schema migration is not compatible")
            .withDetail("state", incompatible.get().getState().getDisplayName())
            .build();
      }
      return Health.up().withDetail("version", current.getVersion().toString()).build();
    } catch (RuntimeException exception) {
      return Health.down(exception).build();
    }
  }
}

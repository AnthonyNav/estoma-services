package com.estoma.authentication.testsupport;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real PostgreSQL database
 * (Flyway migration tests, the health/security ITs -- this service has no
 * repository classes yet, see system_contract.md's Phase 1/Phase 2 split).
 *
 * <p>The container is started once in a static initializer and shared by
 * every subclass in the same JVM: static fields declared on this class are
 * not duplicated per subclass, so all {@code *IT} classes extending this one
 * reuse the same running container instead of paying startup cost per
 * class.
 *
 * <p>Datasource <em>and</em> Flyway connection properties are registered
 * explicitly via {@link DynamicPropertySource} rather than relying on
 * {@code @ServiceConnection} auto-derivation for Flyway: this service ships
 * a default {@code spring.flyway.url/user/password} in application.yml
 * (separate migration credentials, see system_contract.md "Migration
 * Tooling"), and an explicit override here removes any ambiguity about
 * which one wins.
 *
 * <p>{@code @ActiveProfiles("test")} layers {@code application-test.yml}
 * on top of the main config: by default a test context has no real
 * RabbitMQ broker, so the messaging tramo's self-declared topology and
 * scheduled Outbox Relay tick are both switched off here to avoid
 * constant connection-refused noise. {@link AbstractRabbitMqIT} turns
 * topology declaration back on once a real broker is present.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("authentication_db")
          .withUsername("authentication_service")
          .withPassword("authentication_service")
          // Bounds how long any JDBC call can hang against a wedged
          // connection (e.g. a paused/frozen container) -- matches the same
          // socketTimeout/connectTimeout added to the production datasource
          // URL in application.yml.
          .withUrlParam("socketTimeout", "10")
          .withUrlParam("connectTimeout", "10");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void registerPostgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);

    // Single-user container: migration and runtime credentials coincide in
    // tests, exactly like the local-dev default in application.yml.
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("spring.flyway.placeholders.runtime_role", POSTGRES::getUsername);
  }
}

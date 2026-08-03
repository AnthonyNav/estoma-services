package com.estoma.users.testsupport;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real PostgreSQL database but
 * no RabbitMQ broker (repository tests, Flyway migration tests, the
 * transactional dispatcher test).
 *
 * <p>The container is started once in a static initializer and shared by
 * every subclass in the same JVM: static fields declared on this class are
 * not duplicated per subclass, so all {@code *IT} classes extending this one
 * reuse the same running container instead of paying startup cost per class.
 *
 * <p>Datasource <em>and</em> Flyway connection properties are registered
 * explicitly via {@link DynamicPropertySource} rather than relying on
 * {@code @ServiceConnection} auto-derivation for Flyway: this service ships
 * a default {@code spring.flyway.url/user/password} in application.yml (see
 * docs/system-contract.md §12 on separate migration credentials), and an
 * explicit override here removes any ambiguity about which one wins.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
public abstract class AbstractPostgresIT {

  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("users_db")
          .withUsername("users_service")
          .withPassword("users_service");

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

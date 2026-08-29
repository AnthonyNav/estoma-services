package com.estoma.platformbff.application.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.platformbff.infrastructure.config.PlatformProperties;
import com.estoma.platformbff.infrastructure.config.ProjectionGenesisStartup;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exercises the marker and readiness transition against the Flyway-managed PostgreSQL schema. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "platform.genesis.enabled=false",
    "platform.outbox.enabled=false",
    "platform.messaging.listeners-enabled=false",
    "platform.messaging.topology-declare=false"
})
@Tag("integration")
class ProjectionGenesisPostgresIT {
  private static final List<String> TARGETS = List.of(
      "person", "revocation", "academic", "communications", "wash-administration",
      "appointments", "wash-execution");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("bff_genesis").withUsername("bff").withPassword("bff");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired ProjectionGenesisService genesis;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void resetInitialProjectionState() {
    jdbc.sql("TRUNCATE TABLE projection_genesis_markers, projection_status CASCADE").update();
    for (String name : List.of("person", "revocation", "authorization", "academic", "communications",
        "wash-administration", "appointments", "wash-execution")) {
      jdbc.sql("""
          INSERT INTO projection_status(projection_name, status, generation, updated_at)
          VALUES (:name, 'BOOTSTRAPPING', 0, CURRENT_TIMESTAMP)
          """).param("name", name).update();
    }
  }

  @Test
  void establishesTheDurableBaselineOnceAndRestartIsANoOp() {
    assertThat(genesis.establishFreshBaseline()).isTrue();
    assertThat(statuses(TARGETS)).containsOnly("READY");
    assertThat(markerCount()).isEqualTo(1);

    assertThat(genesis.establishFreshBaseline()).isFalse();
    assertThat(statuses(TARGETS)).containsOnly("READY");
    assertThat(markerCount()).isEqualTo(1);
  }

  @Test
  void incompatibleLocalStateFailsBeforeWritingTheMarkerOrAnyReadiness() {
    jdbc.sql("""
        UPDATE projection_status SET status = 'READY'
        WHERE projection_name = 'appointments'
        """).update();

    assertThatThrownBy(() -> genesis.establishFreshBaseline())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("appointments");

    assertThat(markerCount()).isZero();
    assertThat(status("appointments")).isEqualTo("READY");
    assertThat(status("person")).isEqualTo("BOOTSTRAPPING");
  }

  @Test
  void missingMarkerAndEmptyReadModelsDoNotAutoPromoteWithoutFreshDeploymentOptIn() throws Exception {
    new ProjectionGenesisStartup(properties(false, false), genesis).run(null);

    assertThat(markerCount()).isZero();
    assertThat(statuses(TARGETS)).containsOnly("BOOTSTRAPPING");
  }

  @Test
  void historicalEnvironmentAttestationFailsClosedBeforeGenesis() {
    assertThatThrownBy(() -> new ProjectionGenesisStartup(properties(true, false), genesis).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fresh-environment");

    assertThat(markerCount()).isZero();
    assertThat(statuses(TARGETS)).containsOnly("BOOTSTRAPPING");
  }

  private List<String> statuses(List<String> names) {
    return names.stream().map(this::status).toList();
  }

  private String status(String name) {
    return jdbc.sql("SELECT status FROM projection_status WHERE projection_name = :name")
        .param("name", name).query(String.class).single();
  }

  private long markerCount() {
    return jdbc.sql("SELECT count(*) FROM projection_genesis_markers")
        .query(Long.class).single();
  }

  private static PlatformProperties properties(boolean enabled, boolean environmentFresh) {
    return new PlatformProperties("bff", null, null, null,
        new PlatformProperties.Genesis(enabled, environmentFresh));
  }
}

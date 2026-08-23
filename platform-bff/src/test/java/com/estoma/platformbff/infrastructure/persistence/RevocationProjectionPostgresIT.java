package com.estoma.platformbff.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
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

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "platform.outbox.enabled=false",
    "platform.messaging.listeners-enabled=false",
    "platform.messaging.topology-declare=false"
})
@Tag("integration")
class RevocationProjectionPostgresIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("bff_db")
      .withUsername("bff")
      .withPassword("bff");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired JdbcRevocationProjectionRepository repository;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clear() {
    jdbc.sql("""
        TRUNCATE TABLE session_revocations, account_session_revocation_cutoffs,
          person_session_revocation_cutoffs, account_person_projection, inbox_messages CASCADE
        """).update();
  }

  @Test
  void accountCutoffIsMonotonicAndAllWinsOnlyAtSameTimestamp() {
    UUID accountId = UUID.randomUUID();
    UUID retained = UUID.randomUUID();
    Instant now = Instant.now();

    repository.revokeAccount(accountId, 200, "OTHER", retained, "PASSWORD_CHANGED",
        UUID.randomUUID(), now);
    repository.revokeAccount(accountId, 100, "ALL", null, "STALE",
        UUID.randomUUID(), now.plusSeconds(1));
    assertThat(cutoff(accountId)).isEqualTo(new Cutoff(200, "OTHER", retained));

    repository.revokeAccount(accountId, 200, "ALL", null, "SAME_TIMESTAMP_ALL",
        UUID.randomUUID(), now.plusSeconds(2));
    assertThat(cutoff(accountId)).isEqualTo(new Cutoff(200, "ALL", null));

    UUID newerRetained = UUID.randomUUID();
    repository.revokeAccount(accountId, 201, "OTHER", newerRetained, "NEWER_OTHER",
        UUID.randomUUID(), now.plusSeconds(3));
    assertThat(cutoff(accountId)).isEqualTo(new Cutoff(201, "OTHER", newerRetained));

    repository.revokeAccount(accountId, 200, "ALL", null, "OLDER_ALL",
        UUID.randomUUID(), now.plusSeconds(4));
    assertThat(cutoff(accountId)).isEqualTo(new Cutoff(201, "OTHER", newerRetained));
  }

  private Cutoff cutoff(UUID accountId) {
    return jdbc.sql("""
        SELECT revoked_before_or_at, cutoff_kind, except_session_id
        FROM account_session_revocation_cutoffs
        WHERE account_id = :accountId
        """)
        .param("accountId", accountId)
        .query((rs, row) -> new Cutoff(
            rs.getLong("revoked_before_or_at"),
            rs.getString("cutoff_kind"),
            rs.getObject("except_session_id", UUID.class)))
        .single();
  }

  private record Cutoff(long timestamp, String kind, UUID exceptSessionId) {}
}

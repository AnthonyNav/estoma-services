package com.estoma.platformbff.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.platformbff.application.port.ReassignmentCandidatesPort.Candidate;
import com.estoma.platformbff.application.port.ReassignmentCandidatesPort.Snapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
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
    "platform.messaging.topology-declare=false",
    "platform.wash.reassignment-candidates.enabled=false"
})
@DirtiesContext
@Tag("integration")
class ReassignmentCandidatesPostgresIT {
  private static final Instant NOW = Instant.parse("2026-09-05T18:00:00Z");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("bff_db").withUsername("bff").withPassword("bff");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired JdbcReassignmentCandidatesRepository repository;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clearTables() {
    jdbc.sql("DELETE FROM reassignment_candidate_read_model").update();
    jdbc.sql("DELETE FROM reassignment_candidates_read_model").update();
    jdbc.sql("DELETE FROM reassignment_candidates_refresh_history").update();
    jdbc.sql("DELETE FROM reassignment_candidates_refresh").update();
    jdbc.sql("DELETE FROM wash_execution_read_model").update();
  }

  @Test
  void sweepsBeyondOneBatchWithoutAnInflightRowStarvingUnseenExecutions() {
    List<UUID> ids = new ArrayList<>();
    for (int index = 0; index < 30; index++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      execution(id, 1, "PENDING_REASSIGNMENT");
    }

    assertThat(repository.enqueueDuePending(NOW, NOW.minusSeconds(5), NOW.minusSeconds(30), 10))
        .isEqualTo(10);
    assertThat(repository.enqueueDuePending(NOW.plusSeconds(1), NOW.minusSeconds(4),
        NOW.minusSeconds(29), 10)).isEqualTo(10);

    Integer refreshes = jdbc.sql("SELECT count(*) FROM reassignment_candidates_refresh")
        .query(Integer.class).single();
    assertThat(refreshes).isEqualTo(20);
  }

  @Test
  void archivesRolloverIdentityAndBenignlyRefusesItsLateResult() {
    UUID id = UUID.randomUUID();
    execution(id, 1, "PENDING_REASSIGNMENT");
    assertThat(repository.enqueueDuePending(NOW, NOW.minusSeconds(5), NOW.minusSeconds(30), 1))
        .isEqualTo(1);
    var old = repository.claimNext(NOW).orElseThrow();
    assertThat(repository.markPublished(old.messageId(), old.claimToken(), NOW)).isTrue();
    var published = repository.findRefreshByMessageId(old.messageId()).orElseThrow();

    jdbc.sql("UPDATE wash_execution_read_model SET execution_version = 2 WHERE wash_execution_id = :id")
        .param("id", id).update();
    assertThat(repository.enqueueDuePending(NOW.plusSeconds(1), NOW.minusSeconds(4),
        NOW.minusSeconds(29), 1)).isEqualTo(1);
    assertThat(repository.findRefreshByMessageId(old.messageId())).contains(published);

    Snapshot stale = snapshot(id, 1, UUID.randomUUID(), NOW, List.of(candidate()));
    assertThat(repository.applySnapshot(published, stale.resultMessageId(), stale, NOW.plusSeconds(1)))
        .isFalse();
    assertThat(repository.findSnapshot(id)).isEmpty();
  }

  @Test
  void currentResultPersistsOwnerOrderButExecutionChangeFencesLaterOverwrite() {
    UUID id = UUID.randomUUID();
    execution(id, 3, "PENDING_REASSIGNMENT");
    repository.enqueueDuePending(NOW, NOW.minusSeconds(5), NOW.minusSeconds(30), 1);
    var refresh = repository.claimNext(NOW).orElseThrow();
    repository.markPublished(refresh.messageId(), refresh.claimToken(), NOW);
    Snapshot snapshot = snapshot(id, 3, UUID.randomUUID(), NOW,
        List.of(candidate(), candidate()));
    assertThat(repository.applySnapshot(refresh, snapshot.resultMessageId(), snapshot,
        NOW.plusSeconds(1))).isTrue();
    assertThat(repository.findSnapshot(id)).contains(snapshot);

    jdbc.sql("UPDATE wash_execution_read_model SET status = 'IN_PROGRESS' WHERE wash_execution_id = :id")
        .param("id", id).update();
    assertThat(repository.rejectRefresh(refresh, UUID.randomUUID(), "REASSIGNMENT_NOT_PENDING",
        NOW.plusSeconds(2))).isFalse();
  }

  private void execution(UUID id, long version, String status) {
    jdbc.sql("""
        INSERT INTO wash_execution_read_model (
          wash_execution_id, status, execution_version, updated_at)
        VALUES (:id, :status, :version, :at)
        """).param("id", id).param("status", status).param("version", version)
        .param("at", JdbcTimes.timestamp(NOW)).update();
  }

  private static Snapshot snapshot(UUID id, long version, UUID resultId, Instant generatedAt,
      List<Candidate> values) {
    return new Snapshot(id, version, resultId, generatedAt, generatedAt.plusSeconds(10), values);
  }

  private static Candidate candidate() {
    UUID cabin = UUID.randomUUID();
    UUID tank = UUID.randomUUID();
    return new Candidate(cabin, cabin.toString(), "Cabin", tank, tank.toString(), "Tank", 2);
  }
}

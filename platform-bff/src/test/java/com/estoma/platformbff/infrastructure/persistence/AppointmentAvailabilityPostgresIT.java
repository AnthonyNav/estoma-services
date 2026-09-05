package com.estoma.platformbff.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.AvailableTimeSlot;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Context;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Refresh;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Snapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    "platform.messaging.topology-declare=false"
})
@DirtiesContext
@Tag("integration")
class AppointmentAvailabilityPostgresIT {
  private static final Instant REQUESTED_AT = Instant.parse("2026-09-04T18:00:00Z");
  private static final Context CONTEXT = new Context(UUID.randomUUID(), LocalDate.of(2026, 9, 4),
      "NORMAL", 15, "HIGH_SPEED", UUID.randomUUID());

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
  }

  @Autowired JdbcAppointmentAvailabilityRepository repository;

  @Test
  void storesOwnerSnapshotAndFencesAResultFromAReplacedRefresh() {
    Refresh first = Refresh.pending(CONTEXT, REQUESTED_AT);
    assertThat(repository.enqueueRefresh(first, REQUESTED_AT.minusSeconds(30))).isTrue();
    Refresh duplicate = Refresh.pending(CONTEXT, REQUESTED_AT.plusSeconds(1));
    assertThat(repository.enqueueRefresh(duplicate, REQUESTED_AT.minusSeconds(29))).isFalse();
    assertThat(repository.findRefreshByMessageId(first.messageId())).contains(first);
    Snapshot firstSnapshot = snapshot(UUID.randomUUID(), REQUESTED_AT.plusSeconds(1),
        REQUESTED_AT.plusSeconds(5), 4);
    repository.applySnapshot(first, firstSnapshot.snapshotId(), firstSnapshot,
        REQUESTED_AT.plusSeconds(2));

    assertThat(repository.findSnapshot(CONTEXT)).contains(firstSnapshot);

    Refresh replacement = Refresh.pending(CONTEXT, REQUESTED_AT.plusSeconds(6));
    assertThat(repository.enqueueRefresh(replacement, REQUESTED_AT.minusSeconds(24))).isTrue();
    Snapshot staleSnapshot = snapshot(UUID.randomUUID(), REQUESTED_AT.plusSeconds(3),
        REQUESTED_AT.plusSeconds(20), 2);
    assertThatThrownBy(() -> repository.applySnapshot(first, staleSnapshot.snapshotId(),
        staleSnapshot, REQUESTED_AT.plusSeconds(4)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stale or unsolicited");
    assertThat(repository.findSnapshot(CONTEXT)).contains(firstSnapshot);

    Snapshot replacementSnapshot = snapshot(UUID.randomUUID(), REQUESTED_AT.plusSeconds(6),
        REQUESTED_AT.plusSeconds(36), 7);
    repository.applySnapshot(replacement, replacementSnapshot.snapshotId(), replacementSnapshot,
        REQUESTED_AT.plusSeconds(32));
    assertThat(repository.findSnapshot(CONTEXT)).contains(replacementSnapshot);
  }

  private static Snapshot snapshot(UUID snapshotId, Instant generatedAt, Instant freshUntil,
      int capacity) {
    return new Snapshot(CONTEXT, snapshotId, generatedAt, freshUntil, true, List.of(), 0, 0,
        false, false, null, List.of(new AvailableTimeSlot(UUID.randomUUID(),
            generatedAt.plusSeconds(60), generatedAt.plusSeconds(120), capacity, freshUntil)));
  }
}

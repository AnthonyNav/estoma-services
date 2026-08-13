package com.estoma.washadministration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washadministration.application.port.CabinRepository;
import com.estoma.washadministration.application.port.TankRepository;
import com.estoma.washadministration.domain.resource.Cabin;
import com.estoma.washadministration.domain.resource.CabinType;
import com.estoma.washadministration.domain.resource.ResourceStatus;
import com.estoma.washadministration.domain.resource.Tank;
import com.estoma.washadministration.domain.shared.DisplayName;
import com.estoma.washadministration.domain.shared.DomainErrorCode;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.PositiveCapacity;
import com.estoma.washadministration.domain.shared.ResourceCode;
import com.estoma.washadministration.testsupport.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ConstraintConcurrencyIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private DataSource dataSource;
  @Autowired private JdbcClient jdbc;
  @Autowired private CabinRepository cabins;
  @Autowired private TankRepository tanks;

  @Test
  void activeCalendarOverlapIsRaceSafe() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        UUID id = UUID.randomUUID();
        results.add(executor.submit(() -> insertOverlappingCalendar(id, ready, go)));
      }
      ready.await();
      go.countDown();
      assertThat(results.get(0).get()).isNotEqualTo(results.get(1).get());
    } finally {
      executor.shutdownNow();
    }
    assertThat(count("service_calendars")).isEqualTo(1);
  }

  @Test
  void activeScheduleOverlapIsRaceSafe() throws Exception {
    UUID calendarId = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO service_calendars (service_calendar_id, code, name, timezone, effective_from, effective_until, status, version, created_by_account_id, updated_by_account_id, created_at, updated_at) "
                + "VALUES (?, ?, 'Calendar', 'UTC', ?, ?, 'DRAFT', 1, ?, ?, ?, ?)")
        .params(
            calendarId,
            "CAL-" + UUID.randomUUID(),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 12, 31),
            UUID.randomUUID(),
            UUID.randomUUID(),
            OffsetDateTime.now(),
            OffsetDateTime.now())
        .update();

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        results.add(executor.submit(() -> insertOverlappingSchedule(calendarId, ready, go)));
      }
      ready.await();
      go.countDown();
      assertThat(results.get(0).get()).isNotEqualTo(results.get(1).get());
    } finally {
      executor.shutdownNow();
    }
    assertThat(count("service_schedules")).isEqualTo(1);
  }

  @Test
  void uniqueCodesAreRaceSafe() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        results.add(executor.submit(() -> insertCabinWithSameCode(ready, go)));
      }
      ready.await();
      go.countDown();
      assertThat(results.get(0).get()).isNotEqualTo(results.get(1).get());
    } finally {
      executor.shutdownNow();
    }
    assertThat(count("cabins")).isEqualTo(1);
  }

  @Test
  void cabinRetirementAndTankRegistrationSerializeOnTheParentCabin() throws Exception {
    Cabin cabin =
        Cabin.createNew(
            UUID.randomUUID(),
            new ResourceCode("RACE-PARENT"),
            new DisplayName("Race Parent"),
            CabinType.NORMAL,
            ACCOUNT_ID,
            NOW);
    cabins.insert(cabin);

    Cabin retiredCandidate = cabin.retire(ACCOUNT_ID, true, NOW.plusSeconds(1));
    Tank tank =
        Tank.createNew(
            UUID.randomUUID(),
            cabin.id(),
            new ResourceCode("RACE-TANK"),
            new DisplayName("Race Tank"),
            new PositiveCapacity(1),
            ACCOUNT_ID,
            NOW.plusSeconds(1));

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> retireResult =
          executor.submit(
              () -> {
                ready.countDown();
                await(go);
                try {
                  return cabins.update(retiredCandidate, cabin.version()) == 1;
                } catch (DomainRuleViolationException exception) {
                  assertThat(exception.code()).isEqualTo(DomainErrorCode.CABIN_HAS_NON_RETIRED_TANKS);
                  return false;
                }
              });

      Future<Boolean> registerResult =
          executor.submit(
              () -> {
                ready.countDown();
                await(go);
                try {
                  tanks.insert(tank);
                  return true;
                } catch (DomainRuleViolationException exception) {
                  assertThat(exception.code()).isEqualTo(DomainErrorCode.CABIN_RETIRED);
                  return false;
                }
              });

      ready.await();
      go.countDown();

      boolean retired = retireResult.get();
      boolean registered = registerResult.get();
      assertThat(retired).isNotEqualTo(registered);
    } finally {
      executor.shutdownNow();
    }

    Cabin stored = cabins.findById(cabin.id()).orElseThrow();
    long nonRetiredTanks = tanks.countNonRetiredByCabinId(cabin.id());
    assertThat(stored.status() == ResourceStatus.RETIRED && nonRetiredTanks > 0).isFalse();
    if (stored.status() == ResourceStatus.RETIRED) {
      assertThat(nonRetiredTanks).isZero();
    } else {
      assertThat(nonRetiredTanks).isEqualTo(1);
    }
  }

  private boolean insertOverlappingCalendar(UUID id, CountDownLatch ready, CountDownLatch go) {
    ready.countDown();
    await(go);
    return inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO service_calendars (service_calendar_id, code, name, timezone, effective_from, effective_until, status, version, created_by_account_id, updated_by_account_id, created_at, updated_at) "
                      + "VALUES (?, ?, 'Calendar', 'UTC', ?, ?, 'ACTIVE', 1, ?, ?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, "CAL-" + id);
            statement.setObject(3, LocalDate.of(2026, 1, 1));
            statement.setObject(4, LocalDate.of(2026, 12, 31));
            statement.setObject(5, UUID.randomUUID());
            statement.setObject(6, UUID.randomUUID());
            statement.setObject(7, OffsetDateTime.now());
            statement.setObject(8, OffsetDateTime.now());
            statement.executeUpdate();
          }
        });
  }

  private boolean insertOverlappingSchedule(UUID calendarId, CountDownLatch ready, CountDownLatch go) {
    ready.countDown();
    await(go);
    return inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO service_schedules (service_schedule_id, service_calendar_id, day_of_week, opening_time, closing_time, slot_duration_minutes, status, version, created_by_account_id, updated_by_account_id, created_at, updated_at) "
                      + "VALUES (?, ?, 1, ?, ?, 60, 'ACTIVE', 1, ?, ?, ?, ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, calendarId);
            statement.setObject(3, LocalTime.of(8, 0));
            statement.setObject(4, LocalTime.of(12, 0));
            statement.setObject(5, UUID.randomUUID());
            statement.setObject(6, UUID.randomUUID());
            statement.setObject(7, OffsetDateTime.now());
            statement.setObject(8, OffsetDateTime.now());
            statement.executeUpdate();
          }
        });
  }

  private boolean insertCabinWithSameCode(CountDownLatch ready, CountDownLatch go) {
    ready.countDown();
    await(go);
    return inTransaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO cabins (cabin_id, code, name, cabin_type, status, version, created_by_account_id, updated_by_account_id, created_at, updated_at) "
                      + "VALUES (?, 'RACE-CABIN', 'Cabin', 'NORMAL', 'ACTIVE', 1, ?, ?, ?, ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, UUID.randomUUID());
            statement.setObject(3, UUID.randomUUID());
            statement.setObject(4, OffsetDateTime.now());
            statement.setObject(5, OffsetDateTime.now());
            statement.executeUpdate();
          }
        });
  }

  private boolean inTransaction(SqlWork work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        work.run(connection);
        connection.commit();
        return true;
      } catch (SQLException exception) {
        connection.rollback();
        return false;
      }
    } catch (SQLException exception) {
      return false;
    }
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection connection) throws SQLException;
  }
}

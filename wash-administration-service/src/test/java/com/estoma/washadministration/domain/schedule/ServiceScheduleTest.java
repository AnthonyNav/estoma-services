package com.estoma.washadministration.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.TimeWindow;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServiceScheduleTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID CALENDAR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

  @Test
  void scheduleStartsActiveAtVersionOne() {
    ServiceSchedule schedule = schedule();

    assertThat(schedule.status()).isEqualTo(ServiceScheduleStatus.ACTIVE);
    assertThat(schedule.version()).isEqualTo(1);
  }

  @Test
  void rejectsDayOutsideIsoWeek() {
    assertThatThrownBy(
            () ->
                ServiceSchedule.createNew(
                    UUID.randomUUID(),
                    CALENDAR_ID,
                    8,
                    new TimeWindow(LocalTime.of(8, 0), LocalTime.of(9, 0), 60),
                    ACCOUNT_ID,
                    NOW))
        .isInstanceOf(DomainRuleViolationException.class);
  }

  @Test
  void deactivationIsIrreversibleInV1() {
    ServiceSchedule inactive = schedule().deactivate(ACCOUNT_ID, NOW.plusSeconds(1));

    assertThat(inactive.status()).isEqualTo(ServiceScheduleStatus.INACTIVE);
    assertThatThrownBy(() -> inactive.deactivate(ACCOUNT_ID, NOW.plusSeconds(2)))
        .isInstanceOf(DomainRuleViolationException.class);
  }

  private static ServiceSchedule schedule() {
    return ServiceSchedule.createNew(
        UUID.fromString("20000000-0000-0000-0000-000000000001"),
        CALENDAR_ID,
        1,
        new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0), 60),
        ACCOUNT_ID,
        NOW);
  }
}

package com.estoma.washadministration.domain.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.domain.shared.DisplayName;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.EffectivePeriod;
import com.estoma.washadministration.domain.shared.ResourceCode;
import com.estoma.washadministration.domain.shared.TimeZoneId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServiceCalendarTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void newCalendarStartsAsDraftAtVersionOne() {
    ServiceCalendar calendar = calendar();

    assertThat(calendar.status()).isEqualTo(ServiceCalendarStatus.DRAFT);
    assertThat(calendar.version()).isEqualTo(1);
    assertThat(calendar.code().value()).isEqualTo("2026-A");
  }

  @Test
  void updatePreservesCodeAndIncrementsVersion() {
    ServiceCalendar updated =
        calendar()
            .withUpdatedDetails(
                new DisplayName("Updated"),
                new TimeZoneId("UTC"),
                calendar().effectivePeriod(),
                ACCOUNT_ID,
                NOW.plusSeconds(1));

    assertThat(updated.code().value()).isEqualTo("2026-A");
    assertThat(updated.name().value()).isEqualTo("Updated");
    assertThat(updated.version()).isEqualTo(2);
  }

  @Test
  void onlyActiveCalendarsCanBeDeactivatedAndRetiredStateIsNotReactivated() {
    assertThatThrownBy(() -> calendar().deactivate(ACCOUNT_ID, NOW.plusSeconds(1)))
        .isInstanceOf(DomainRuleViolationException.class);

    ServiceCalendar active = calendar().activate(ACCOUNT_ID, NOW.plusSeconds(1));
    ServiceCalendar inactive = active.deactivate(ACCOUNT_ID, NOW.plusSeconds(2));
    assertThat(inactive.status()).isEqualTo(ServiceCalendarStatus.INACTIVE);
    assertThat(inactive.version()).isEqualTo(3);
  }

  private static ServiceCalendar calendar() {
    return ServiceCalendar.createNew(
        UUID.fromString("10000000-0000-0000-0000-000000000001"),
        new ResourceCode("2026-A"),
        new DisplayName("Calendar"),
        new TimeZoneId("UTC"),
        new EffectivePeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
        ACCOUNT_ID,
        NOW);
  }
}

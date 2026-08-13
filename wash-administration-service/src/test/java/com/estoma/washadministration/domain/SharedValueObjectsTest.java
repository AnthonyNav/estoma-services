package com.estoma.washadministration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.domain.shared.ClosureReason;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.EffectivePeriod;
import com.estoma.washadministration.domain.shared.PositiveCapacity;
import com.estoma.washadministration.domain.shared.ResourceCode;
import com.estoma.washadministration.domain.shared.TimeWindow;
import com.estoma.washadministration.domain.shared.TimeZoneId;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class SharedValueObjectsTest {

  @Test
  void trimsCodesAndReasons() {
    assertThat(new ResourceCode("  cal-1 ").value()).isEqualTo("cal-1");
    assertThat(new ClosureReason("  maintenance ").value()).isEqualTo("maintenance");
  }

  @Test
  void rejectsInvalidPeriodsAndCapacities() {
    assertThatThrownBy(
            () -> new EffectivePeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 31)))
        .isInstanceOf(DomainRuleViolationException.class);
    assertThatThrownBy(() -> new PositiveCapacity(0)).isInstanceOf(DomainRuleViolationException.class);
  }

  @Test
  void periodOverlapTreatsBoundariesAsInclusive() {
    EffectivePeriod first = new EffectivePeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
    EffectivePeriod second = new EffectivePeriod(LocalDate.of(2026, 1, 31), null);

    assertThat(first.overlaps(second)).isTrue();
  }

  @Test
  void scheduleRequiresCompletePositiveMinuteSlots() {
    assertThat(new TimeWindow(LocalTime.of(8, 0), LocalTime.of(17, 0), 60).slotDurationMinutes())
        .isEqualTo(60);
    assertThatThrownBy(() -> new TimeWindow(LocalTime.of(8, 0), LocalTime.of(8, 30), 40))
        .isInstanceOf(DomainRuleViolationException.class);
    assertThatThrownBy(() -> new TimeWindow(LocalTime.of(9, 0), LocalTime.of(8, 0), 60))
        .isInstanceOf(DomainRuleViolationException.class);
  }

  @Test
  void timezoneMustBeAnIanaZone() {
    assertThat(new TimeZoneId("America/Mexico_City").value()).isEqualTo("America/Mexico_City");
    assertThatThrownBy(() -> new TimeZoneId("not/a-zone"))
        .isInstanceOf(DomainRuleViolationException.class);
  }
}

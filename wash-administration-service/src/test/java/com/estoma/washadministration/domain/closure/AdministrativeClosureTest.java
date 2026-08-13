package com.estoma.washadministration.domain.closure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.domain.shared.ClosureReason;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import com.estoma.washadministration.domain.shared.EffectivePeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdministrativeClosureTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID CALENDAR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final EffectivePeriod PERIOD =
      new EffectivePeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

  @Test
  void serviceClosureHasNoResourceReferences() {
    AdministrativeClosure closure =
        AdministrativeClosure.schedule(
            UUID.randomUUID(),
            CALENDAR_ID,
            AdministrativeClosureScope.SERVICE,
            null,
            null,
            Instant.parse("2026-02-01T09:00:00Z"),
            Instant.parse("2026-02-01T10:00:00Z"),
            new ClosureReason("Maintenance"),
            PERIOD,
            ZoneId.of("UTC"),
            ACCOUNT_ID,
            NOW);

    assertThat(closure.status()).isEqualTo(AdministrativeClosureStatus.SCHEDULED);
    assertThat(closure.version()).isEqualTo(1);
  }

  @Test
  void scopeReferencesMustMatchExactly() {
    assertThatThrownBy(
            () ->
                AdministrativeClosure.schedule(
                    UUID.randomUUID(),
                    CALENDAR_ID,
                    AdministrativeClosureScope.SERVICE,
                    UUID.randomUUID(),
                    null,
                    Instant.parse("2026-02-01T09:00:00Z"),
                    Instant.parse("2026-02-01T10:00:00Z"),
                    new ClosureReason("Maintenance"),
                    PERIOD,
                    ZoneId.of("UTC"),
                    ACCOUNT_ID,
                    NOW))
        .isInstanceOf(DomainRuleViolationException.class);
  }

  @Test
  void cancellationRequiresReasonAndIsTerminal() {
    AdministrativeClosure scheduled =
        AdministrativeClosure.schedule(
            UUID.randomUUID(),
            CALENDAR_ID,
            AdministrativeClosureScope.CABIN,
            UUID.randomUUID(),
            null,
            Instant.parse("2026-02-01T09:00:00Z"),
            Instant.parse("2026-02-01T10:00:00Z"),
            new ClosureReason("Maintenance"),
            PERIOD,
            ZoneId.of("UTC"),
            ACCOUNT_ID,
            NOW);

    AdministrativeClosure cancelled =
        scheduled.cancel(ACCOUNT_ID, NOW.plusSeconds(1), new ClosureReason("No longer needed"));
    assertThat(cancelled.status()).isEqualTo(AdministrativeClosureStatus.CANCELLED);
    assertThat(cancelled.version()).isEqualTo(2);
    assertThatThrownBy(
            () -> cancelled.cancel(ACCOUNT_ID, NOW.plusSeconds(2), new ClosureReason("Again")))
        .isInstanceOf(DomainRuleViolationException.class);
  }
}

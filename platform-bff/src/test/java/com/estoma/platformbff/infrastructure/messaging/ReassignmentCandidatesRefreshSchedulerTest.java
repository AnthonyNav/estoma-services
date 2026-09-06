package com.estoma.platformbff.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.estoma.platformbff.application.port.ReassignmentCandidatesPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;

class ReassignmentCandidatesRefreshSchedulerTest {
  private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");
  private final ReassignmentCandidatesPort port = mock(ReassignmentCandidatesPort.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void refreshIsAbsentWhenFlagIsMissing() {
    context().run(context -> {
      assertThat(context).doesNotHaveBean(ReassignmentCandidatesRefreshScheduler.class);
      verifyNoInteractions(port);
    });
  }

  @Test
  void refreshIsAbsentWhenFlagIsExplicitlyDisabled() {
    context().withPropertyValues("platform.wash.reassignment-candidates.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(ReassignmentCandidatesRefreshScheduler.class);
          verifyNoInteractions(port);
        });
  }

  @Test
  void enablingFlagCreatesBoundedBackgroundSweepWithoutRunningItAtConstruction() {
    context().withPropertyValues("platform.wash.reassignment-candidates.enabled=true")
        .run(context -> {
          assertThat(context).hasSingleBean(ReassignmentCandidatesRefreshScheduler.class);
          verifyNoInteractions(port);
          context.getBean(ReassignmentCandidatesRefreshScheduler.class).sweep();
          verify(port).enqueueDuePending(NOW, NOW.minusSeconds(5), NOW.minusSeconds(30), 25);
          verify(port).cleanupInactive(NOW.minusSeconds(3600), 25);
          verifyNoMoreInteractions(port);
        });
  }

  @Test
  void sweepUsesInjectedClockAndConfiguredLimits() {
    scheduler(Duration.ofSeconds(3), Duration.ofMinutes(15), 7).sweep();
    verify(port).enqueueDuePending(NOW, NOW.minusSeconds(3), NOW.minusSeconds(30), 7);
    verify(port).cleanupInactive(NOW.minusSeconds(900), 7);
    verifyNoMoreInteractions(port);
  }

  @Test
  void rejectsRefreshIntervalAtOrBeyondOwnerTtlAndNonpositiveIntervals() {
    for (Duration interval : new Duration[] {
        null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(10), Duration.ofSeconds(11)}) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> scheduler(interval, Duration.ofHours(1), 25));
    }
    verifyNoInteractions(port);
  }

  @Test
  void rejectsUnboundedBatchAndNonpositiveRetention() {
    for (int batch : new int[] {0, -1, 1001}) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> scheduler(Duration.ofSeconds(5), Duration.ofHours(1), batch));
    }
    for (Duration retention : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> scheduler(Duration.ofSeconds(5), retention, 25));
    }
    verifyNoInteractions(port);
  }

  private ApplicationContextRunner context() {
    return new ApplicationContextRunner()
        .withInitializer(context -> context.getBeanFactory()
            .setConversionService(ApplicationConversionService.getSharedInstance()))
        .withBean(ReassignmentCandidatesPort.class, () -> port)
        .withBean(Clock.class, () -> clock)
        .withUserConfiguration(ReassignmentCandidatesRefreshScheduler.class);
  }

  private ReassignmentCandidatesRefreshScheduler scheduler(
      Duration interval, Duration retention, int batch) {
    return new ReassignmentCandidatesRefreshScheduler(port, clock, interval, retention, batch);
  }
}

package com.estoma.washexecution.infrastructure.scheduling;

import com.estoma.washexecution.application.port.CompleteExpiredCleaningPeriodsUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "wash-execution.cleaning-worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public final class CleaningPeriodScheduler {
  private final CompleteExpiredCleaningPeriodsUseCase useCase;

  public CleaningPeriodScheduler(CompleteExpiredCleaningPeriodsUseCase useCase) {
    this.useCase = useCase;
  }

  @Scheduled(fixedDelayString = "${wash-execution.cleaning-worker-delay-ms:30000}")
  public void completeExpiredCleaningPeriods() {
    useCase.completeExpiredCleaningPeriods();
  }
}

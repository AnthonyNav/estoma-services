package com.estoma.washexecution.infrastructure.transaction;

import com.estoma.washexecution.application.cleaning.CleaningPeriodCompletionService;
import com.estoma.washexecution.application.port.CompleteExpiredCleaningPeriodsUseCase;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class TransactionalCleaningPeriodCompletionHandler
    implements CompleteExpiredCleaningPeriodsUseCase {
  private final CleaningPeriodCompletionService service;
  private final TransactionTemplate transactions;

  public TransactionalCleaningPeriodCompletionHandler(
      CleaningPeriodCompletionService service,
      PlatformTransactionManager transactionManager) {
    this.service = service;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public int completeExpiredCleaningPeriods() {
    Integer completed = transactions.execute(status -> service.completeExpiredCleaningPeriods());
    return Objects.requireNonNullElse(completed, 0);
  }
}

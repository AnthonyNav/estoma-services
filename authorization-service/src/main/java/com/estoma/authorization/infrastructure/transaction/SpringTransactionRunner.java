package com.estoma.authorization.infrastructure.transaction;

import com.estoma.authorization.application.port.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Spring adapter for the broker-agnostic transaction port. */
@Component
class SpringTransactionRunner implements TransactionRunner {

  private final TransactionTemplate transactionTemplate;

  SpringTransactionRunner(TransactionTemplate transactionTemplate) {
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public <T> T inTransaction(Supplier<T> operation) {
    return transactionTemplate.execute(status -> operation.get());
  }
}

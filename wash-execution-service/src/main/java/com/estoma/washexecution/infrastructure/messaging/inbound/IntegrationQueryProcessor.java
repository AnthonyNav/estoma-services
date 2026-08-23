package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.application.query.IntegrationQueryMetadata;
import com.estoma.washexecution.application.query.WashExecutionIntegrationQuery;
import com.estoma.washexecution.application.query.WashExecutionIntegrationQueryService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class IntegrationQueryProcessor {
  private final WashExecutionIntegrationQueryService service;
  private final TransactionTemplate transactions;

  public IntegrationQueryProcessor(
      WashExecutionIntegrationQueryService service,
      PlatformTransactionManager manager) {
    this.service = service;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  public void process(IntegrationQueryMetadata metadata, WashExecutionIntegrationQuery query) {
    transactions.executeWithoutResult(status -> service.respond(metadata, query));
  }

  public void reject(IntegrationQueryMetadata metadata, String code) {
    transactions.executeWithoutResult(status -> service.respondRejected(metadata, code));
  }
}

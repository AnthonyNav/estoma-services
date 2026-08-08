package com.estoma.authentication.infrastructure.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Overrides Spring Boot's auto-configured {@code DataSourceTransactionManager}
 * to allow nested transactions (JDBC savepoints).
 *
 * <p>A rejection detected by translating a database constraint violation
 * (e.g. {@code AccountRepository#insert}'s {@code ACCOUNT_ALREADY_EXISTS}/
 * {@code LOGIN_IDENTIFIER_ALREADY_USED}) leaves the surrounding PostgreSQL
 * transaction aborted -- any further statement in that same transaction
 * fails with "current transaction is aborted" until a rollback. The
 * dispatchers (CommandDispatcher, RpcDispatcher) need to append a Rejected
 * result/response in the SAME transaction right after catching that
 * rejection, so those specific repository writes run under {@code
 * Propagation.NESTED}: a savepoint is taken before the write and rolled
 * back to (not the whole transaction) if it throws, letting the rest of the
 * dispatcher's transaction continue normally.
 */
@Configuration
class TransactionManagerConfig {

  @Bean
  PlatformTransactionManager transactionManager(DataSource dataSource) {
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    transactionManager.setNestedTransactionAllowed(true);
    return transactionManager;
  }
}

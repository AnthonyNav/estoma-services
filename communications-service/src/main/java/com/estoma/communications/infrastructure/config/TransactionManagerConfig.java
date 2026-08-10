package com.estoma.communications.infrastructure.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Overrides Spring Boot's auto-configured {@code DataSourceTransactionManager}
 * to allow nested transactions (JDBC savepoints).
 *
 * <p>A rejection detected by translating a database constraint violation
 * (e.g. {@code CommunicationRepository#insert}'s {@code
 * COMMUNICATION_ALREADY_EXISTS}) leaves the surrounding PostgreSQL
 * transaction aborted -- any further statement in that same transaction
 * fails with "current transaction is aborted" until a rollback. The
 * dispatchers ({@code CommandDispatcher}, and {@code
 * AppointmentCancelledAdapter} via {@code ProjectionDispatcher}) need to
 * either append a Rejected result or treat the conflict as an idempotent
 * no-op in the SAME transaction right after catching that rejection, so
 * those specific repository writes run under {@code Propagation.NESTED}: a
 * savepoint is taken before the write and rolled back to (not the whole
 * transaction) if it throws, letting the rest of the transaction continue
 * normally.
 */
@Configuration
class TransactionManagerConfig {

  @Bean
  PlatformTransactionManager transactionManager(DataSource dataSource) {
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    transactionManager.setNestedTransactionAllowed(true);
    return transactionManager;
  }

  @Bean(name = "emailProviderExecutor")
  ThreadPoolTaskExecutor emailProviderExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("communications-email-provider-");
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.initialize();
    return executor;
  }
}

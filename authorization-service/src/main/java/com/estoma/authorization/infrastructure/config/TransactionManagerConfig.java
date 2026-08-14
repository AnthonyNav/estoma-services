package com.estoma.authorization.infrastructure.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Provides JDBC transactions for the application transaction port. */
@Configuration
class TransactionManagerConfig {

  @Bean
  PlatformTransactionManager transactionManager(DataSource dataSource) {
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    transactionManager.setNestedTransactionAllowed(true);
    return transactionManager;
  }
}

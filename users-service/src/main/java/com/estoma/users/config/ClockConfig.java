package com.estoma.users.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single injectable {@link Clock} bean, for deterministic timestamp
 * assertions in future tests. Not yet threaded through every {@code
 * Instant.now()} call site (the four command handlers and the JDBC
 * repositories) — that would be a mechanical refactor across many files
 * for marginal benefit right now, deliberately deferred rather than
 * done partially.
 */
@Configuration
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}

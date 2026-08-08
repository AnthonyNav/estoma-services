package com.estoma.users.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Replaces Spring's default single-threaded {@code @Scheduled} executor
 * with a small pool, so {@code OutboxRelay}, {@code OutboxSweeper}'s
 * three jobs, and {@code OutboxMetrics}'s refresh don't serialize behind
 * each other. {@code waitForTasksToCompleteOnShutdown} + a bounded await
 * matches the documented graceful shutdown sequence
 * (deployment-architecture.md:448-455): finish in-flight work before the
 * process exits, but don't hang forever doing it.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "estoma.scheduling",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SchedulingConfig {

  @Bean
  public TaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("estoma-scheduler-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(10);
    return scheduler;
  }
}

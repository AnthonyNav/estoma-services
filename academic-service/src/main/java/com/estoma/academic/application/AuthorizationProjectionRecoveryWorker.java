package com.estoma.academic.application;

import com.estoma.academic.config.AcademicOutboxProperties;
import com.estoma.academic.persistence.ProjectionRecoveryJobRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Claims pending recovery jobs and publishes one Query per durable attempt. */
@Component
public class AuthorizationProjectionRecoveryWorker {
  private static final Logger log = LoggerFactory.getLogger(AuthorizationProjectionRecoveryWorker.class);

  private final ProjectionRecoveryJobRepository jobs;
  private final AuthorizationProjectionRecoveryService recovery;
  private final AcademicOutboxProperties properties;
  private final java.time.Duration resultTimeout;
  private final java.time.Duration failedRetryDelay;
  private final int maxAttempts;
  private final boolean listenersEnabled;

  public AuthorizationProjectionRecoveryWorker(
      ProjectionRecoveryJobRepository jobs,
      AuthorizationProjectionRecoveryService recovery,
      AcademicOutboxProperties properties,
      @Value("${estoma.authorization-recovery.result-timeout:30m}") java.time.Duration resultTimeout,
      @Value("${estoma.authorization-recovery.failed-retry-delay:15m}") java.time.Duration failedRetryDelay,
      @Value("${estoma.authorization-recovery.max-attempts:3}") int maxAttempts,
      @Value("${estoma.messaging.listeners-enabled:true}") boolean listenersEnabled) {
    this.jobs = jobs;
    this.recovery = recovery;
    this.properties = properties;
    this.resultTimeout = resultTimeout;
    this.failedRetryDelay = failedRetryDelay;
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("authorization-recovery.max-attempts must be positive");
    }
    this.maxAttempts = maxAttempts;
    this.listenersEnabled = listenersEnabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void bootstrapEmptyProjection() {
    if (listenersEnabled && !recovery.hasAuthorizationProjectionRows()) {
      recovery.requestFull();
    }
  }

  @Scheduled(fixedDelayString = "${estoma.authorization-recovery.delay:5s}")
  public void run() {
    for (ProjectionRecoveryJobRepository.ExpiredJob expired
        : jobs.requeueExpiredWaiting(resultTimeout, maxAttempts)) {
      if (expired.full() && "FAILED".equals(expired.status())) {
        jobs.markFailed(expired.recoveryJobId(), Instant.now());
      }
    }
    if (listenersEnabled && jobs.fullRecoveryRetryDue(failedRetryDelay)) {
      recovery.requestFull();
    }
    for (ProjectionRecoveryJobRepository.Job job : jobs.claimPending(10, properties.lease())) {
      try {
        recovery.publishNext(job);
      } catch (RuntimeException ex) {
        Instant now = Instant.now();
        if (job.attempts() + 1 >= maxAttempts) {
          if (jobs.fail(job, "RECOVERY_QUERY_PUBLISH_FAILED", now) == 1 && job.full()) {
            jobs.markFailed(job.recoveryJobId(), now);
          }
        } else {
          jobs.release(job, "RECOVERY_QUERY_PUBLISH_FAILED", now);
        }
        log.warn("authorization recovery Query publication failed jobId={} error={}",
            job.recoveryJobId(), ex.toString());
      }
    }
  }
}

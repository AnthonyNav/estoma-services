package com.estoma.users.messaging.outbound;

import com.estoma.users.config.OutboxProperties;
import com.estoma.users.observability.OutboxMetrics;
import com.estoma.users.persistence.ExpiredLease;
import com.estoma.users.persistence.JdbcInboxRepository;
import com.estoma.users.persistence.JdbcOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping for the Transactional Outbox and Consumer Inbox. Does not
 * publish anything — that is {@code OutboxRelay}'s job. This class only:
 *
 * <ol>
 *   <li>reclaims PROCESSING rows whose lease expired, one at a time,
 *       through the same fenced transitions the relay itself uses — a
 *       worker that crashed, was interrupted, or otherwise never marked a
 *       claimed row published or released it (docs/system-contract.md
 *       §8);
 *   <li>marks PENDING rows FAILED once they exhaust their retry budget,
 *       as a defensive backstop (the primary transition happens inline in
 *       {@code OutboxRelay});
 *   <li>purges PUBLISHED outbox rows and processed Inbox records past
 *       their retention window (docs/system-contract.md §10, D8).
 * </ol>
 */
@Component
public class OutboxSweeper {

  private static final Logger log = LoggerFactory.getLogger(OutboxSweeper.class);

  private final JdbcOutboxRepository outboxRepository;
  private final JdbcInboxRepository inboxRepository;
  private final OutboxProperties properties;
  private final OutboxBackoffPolicy backoffPolicy;
  private final OutboxMetrics metrics;
  private final Clock clock;

  OutboxSweeper(
      JdbcOutboxRepository outboxRepository,
      JdbcInboxRepository inboxRepository,
      OutboxProperties properties,
      OutboxBackoffPolicy backoffPolicy,
      OutboxMetrics metrics,
      Clock clock) {
    this.outboxRepository = outboxRepository;
    this.inboxRepository = inboxRepository;
    this.properties = properties;
    this.backoffPolicy = backoffPolicy;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 30_000)
  public void releaseExpiredLeases() {
    List<ExpiredLease> expired = outboxRepository.findExpiredLeases(properties.claimBatchSize());
    for (ExpiredLease lease : expired) {
      reclaim(lease);
    }
  }

  private void reclaim(ExpiredLease lease) {
    Instant now = clock.instant();
    int rows =
        lease.attempts() >= properties.maxAttempts()
            ? outboxRepository.markFailed(lease.messageId(), lease.claimToken(), OutboxFailureCode.LEASE_EXPIRED)
            : outboxRepository.releaseForRetry(
                lease.messageId(),
                lease.claimToken(),
                now.plus(backoffPolicy.delayForAttempt(lease.attempts())),
                OutboxFailureCode.LEASE_EXPIRED);

    if (rows == 0) {
      // Zero rows: the original worker marked this row itself (published,
      // retried, or failed) between findExpiredLeases() reading it and
      // this reclaim attempt, or another sweeper pass already reclaimed
      // it. Either way this sweeper pass has no right to write onto the
      // row anymore -- log and count it, never persist a code
      // (docs/system-contract.md §8, §10; same rule OutboxRelay follows).
      log.warn("CLAIM_LOST_BEFORE_MARK: messageId={} claimToken={} during=sweeper", lease.messageId(), lease.claimToken());
      metrics.claimLost();
      return;
    }
    log.warn(
        "outbox relay lease expired for message {} (attempts={}); reclaimed", lease.messageId(), lease.attempts());
  }

  @Scheduled(fixedDelay = 60_000)
  public void failExhaustedMessages() {
    int failed = outboxRepository.failExhaustedPending(properties.maxAttempts());
    if (failed > 0) {
      log.error(
          "{} outbox message(s) exhausted {} attempt(s) and were marked FAILED; manual intervention required",
          failed,
          properties.maxAttempts());
    }
  }

  @Scheduled(cron = "0 0 3 * * *")
  public void purgeRetainedRecords() {
    Instant now = clock.instant();
    int purgedOutbox = outboxRepository.purgePublishedOlderThan(now.minus(properties.publishedRetention()));
    int purgedInbox = inboxRepository.purgeProcessedOlderThan(now.minus(properties.inboxRetention()));
    log.info(
        "outbox_sweeper purge: {} PUBLISHED outbox row(s), {} processed_messages row(s)",
        purgedOutbox,
        purgedInbox);
  }
}

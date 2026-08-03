package com.estoma.users.messaging.outbound;

import com.estoma.users.config.OutboxProperties;
import com.estoma.users.persistence.JdbcInboxRepository;
import com.estoma.users.persistence.JdbcOutboxRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping for the Transactional Outbox and Consumer Inbox. Does not
 * publish anything — that is {@code OutboxRelay}'s job (added once AMQP
 * publishing exists). This class only:
 *
 * <ol>
 *   <li>reclaims PROCESSING rows whose lease expired (a relay instance
 *       crashed after claiming, before marking published or releasing);
 *   <li>marks PENDING rows FAILED once they exhaust their retry budget,
 *       so they stop being claimed forever;
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

  OutboxSweeper(
      JdbcOutboxRepository outboxRepository,
      JdbcInboxRepository inboxRepository,
      OutboxProperties properties) {
    this.outboxRepository = outboxRepository;
    this.inboxRepository = inboxRepository;
    this.properties = properties;
  }

  @Scheduled(fixedDelay = 30_000)
  public void releaseExpiredLeases() {
    int released = outboxRepository.releaseExpiredLeases();
    if (released > 0) {
      log.warn("outbox relay lease expired for {} message(s); released back to PENDING", released);
    }
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
    Instant now = Instant.now();
    int purgedOutbox = outboxRepository.purgePublishedOlderThan(now.minus(properties.publishedRetention()));
    int purgedInbox = inboxRepository.purgeProcessedOlderThan(now.minus(properties.inboxRetention()));
    log.info(
        "outbox_sweeper purge: {} PUBLISHED outbox row(s), {} processed_messages row(s)",
        purgedOutbox,
        purgedInbox);
  }
}

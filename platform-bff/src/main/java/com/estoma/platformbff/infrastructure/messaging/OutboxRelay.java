package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.port.OutboxRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "platform.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRepository outbox;
  private final ConfirmedCommandPublisher publisher;
  private final int batchSize;
  private final int maxAttempts;
  private final String workerId = UUID.randomUUID().toString();

  public OutboxRelay(
      OutboxRepository outbox,
      ConfirmedCommandPublisher publisher,
      @Value("${platform.outbox.batch-size:50}") int batchSize,
      @Value("${platform.outbox.max-attempts:10}") int maxAttempts) {
    if (batchSize < 1 || maxAttempts < 1) {
      throw new IllegalArgumentException("outbox batch-size and max-attempts must be positive");
    }
    this.outbox = outbox;
    this.publisher = publisher;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
  }

  @Scheduled(fixedDelayString = "${platform.outbox.poll-interval:250ms}")
  public void relay() {
    for (int processed = 0; processed < batchSize; processed++) {
      var claimed = outbox.claimBatch(workerId, Instant.now(), 1);
      if (claimed.isEmpty()) {
        return;
      }
      var message = claimed.getFirst();
      try {
        publisher.publish(message);
        if (!outbox.markPublished(message.messageId(), message.claimToken(), Instant.now())) {
          log.warn("lost Outbox claim before PUBLISHED transition messageId={}", message.messageId());
        }
      } catch (RuntimeException failure) {
        boolean exhausted = message.attemptCount() >= maxAttempts;
        Instant nextAttemptAt = exhausted ? null : Instant.now().plusSeconds(backoffSeconds(message.attemptCount()));
        String errorCode = exhausted ? "PUBLISH_RETRY_EXHAUSTED" : "PUBLISH_FAILED";
        if (!outbox.markFailed(message.messageId(), message.claimToken(), errorCode, nextAttemptAt)) {
          log.warn("lost Outbox claim before FAILED transition messageId={}", message.messageId());
        }
      }
    }
  }

  private static long backoffSeconds(int attemptCount) {
    return Math.min(300L, 1L << Math.min(8, Math.max(0, attemptCount - 1)));
  }
}

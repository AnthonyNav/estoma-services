package com.estoma.users.persistence;

import com.estoma.users.application.port.InboxPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of the Consumer Inbox. The whole idempotency
 * guarantee lives in one {@code INSERT ... ON CONFLICT DO NOTHING}
 * statement: a rows-affected count of 0 means this {@code (consumerName,
 * messageId)} pair was already processed. This is deliberately not
 * "check-then-insert" — that would be a TOCTOU race under concurrent
 * redelivery to two replicas, the exact failure the Inbox exists to
 * prevent (docs/system-contract.md §7, §13).
 */
@Repository
public class JdbcInboxRepository implements InboxPort {

  private final JdbcClient jdbc;

  JdbcInboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean tryInsert(String consumerName, UUID messageId, UUID correlationId) {
    int rowsAffected =
        jdbc.sql(
                """
                INSERT INTO processed_messages (consumer_name, message_id, correlation_id, processed_at)
                VALUES (:consumerName, :messageId, :correlationId, :processedAt)
                ON CONFLICT (consumer_name, message_id) DO NOTHING
                """)
            .param("consumerName", consumerName)
            .param("messageId", messageId)
            .param("correlationId", correlationId)
            .param("processedAt", Timestamps.toTimestamp(Instant.now()))
            .update();
    return rowsAffected == 1;
  }

  /**
   * Deletes processed-message records older than {@code threshold}
   * (docs/system-contract.md §10, D8 — interim 30-day retention). Purging
   * too aggressively re-opens the duplicate-processing window for a very
   * late redelivery, so this is deliberately a long, configurable window
   * rather than an aggressive default.
   */
  public int purgeProcessedOlderThan(Instant threshold) {
    return jdbc.sql("DELETE FROM processed_messages WHERE processed_at < :threshold")
        .param("threshold", Timestamps.toTimestamp(threshold))
        .update();
  }
}

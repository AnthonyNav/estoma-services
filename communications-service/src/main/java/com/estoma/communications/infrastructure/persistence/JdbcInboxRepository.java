package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.InboxPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of the Consumer Inbox. The whole idempotency
 * guarantee lives in one {@code INSERT ... ON CONFLICT DO NOTHING}
 * statement: a rows-affected count of 0 means this {@code (consumerName,
 * messageId)} pair was already processed. Deliberately not "check then
 * insert" -- that would be a TOCTOU race under concurrent redelivery to two
 * replicas, the exact failure the Inbox exists to prevent.
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

  /** Purges rows older than {@code threshold} (system_contract.md "Migration Tooling": 30-day retention). */
  public int purgeProcessedOlderThan(Instant threshold) {
    return jdbc.sql("DELETE FROM processed_messages WHERE processed_at < :threshold")
        .param("threshold", Timestamps.toTimestamp(threshold))
        .update();
  }
}

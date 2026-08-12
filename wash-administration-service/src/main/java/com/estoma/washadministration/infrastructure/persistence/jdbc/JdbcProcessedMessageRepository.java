package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.ProcessedMessageRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcProcessedMessageRepository implements ProcessedMessageRepository {

  private final JdbcClient jdbc;

  JdbcProcessedMessageRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean registerIfAbsent(String consumerName, UUID messageId, UUID correlationId, Instant processedAt) {
    return jdbc.sql(
            """
            INSERT INTO processed_messages (consumer_name, message_id, correlation_id, processed_at)
            VALUES (:consumerName, :messageId, :correlationId, :processedAt)
            ON CONFLICT (consumer_name, message_id) DO NOTHING
            """)
        .param("consumerName", consumerName)
        .param("messageId", messageId)
        .param("correlationId", correlationId)
        .param("processedAt", Timestamps.toTimestamp(processedAt))
        .update()
        == 1;
  }
}

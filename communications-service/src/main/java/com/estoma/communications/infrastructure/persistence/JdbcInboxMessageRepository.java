package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.InboxMessageRepository;
import com.estoma.communications.domain.inbox.InboxMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInboxMessageRepository implements InboxMessageRepository {

  private final JdbcClient jdbc;

  JdbcInboxMessageRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(InboxMessage message) {
    jdbc.sql(
            """
            INSERT INTO inbox_messages (inbox_message_id, communication_id, title, body, status, created_at, expires_at)
            VALUES (:inboxMessageId, :communicationId, :title, :body, CAST(:status AS inbox_message_status), :createdAt, :expiresAt)
            """)
        .param("inboxMessageId", message.inboxMessageId())
        .param("communicationId", message.communicationId())
        .param("title", message.title())
        .param("body", message.body())
        .param("status", message.status().name())
        .param("createdAt", Timestamps.toTimestamp(message.createdAt()))
        .param("expiresAt", Timestamps.toTimestamp(message.expiresAt()))
        .update();
  }

  @Override
  public List<ExpiredInboxMessage> expireDue(Instant cutoff, int batchSize) {
    return jdbc.sql(
            """
            WITH due AS (
              SELECT inbox_message_id FROM inbox_messages
              WHERE status = CAST('ACTIVE' AS inbox_message_status) AND expires_at IS NOT NULL AND expires_at <= :cutoff
              ORDER BY expires_at
              LIMIT :batchSize
              FOR UPDATE SKIP LOCKED
            )
            UPDATE inbox_messages m
            SET status = CAST('EXPIRED' AS inbox_message_status)
            FROM due
            WHERE m.inbox_message_id = due.inbox_message_id
            RETURNING m.inbox_message_id, m.communication_id
            """)
        .param("cutoff", Timestamps.toTimestamp(cutoff))
        .param("batchSize", batchSize)
        .query(JdbcInboxMessageRepository::mapExpired)
        .list();
  }

  private static ExpiredInboxMessage mapExpired(ResultSet rs, int rowNum) throws SQLException {
    return new ExpiredInboxMessage(rs.getObject("inbox_message_id", UUID.class), rs.getObject("communication_id", UUID.class));
  }
}

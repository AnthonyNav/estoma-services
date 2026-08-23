package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.domain.messaging.InboxMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInboxRepository implements InboxRepository {
  private final JdbcClient jdbc;

  public JdbcInboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean recordIfNew(InboxMessage message) {
    return jdbc.sql("""
        INSERT INTO inbox_messages (
          consumer_name, message_id, message_kind, message_type, message_version,
          producer, correlation_id, causation_id, operation_id, aggregate_id,
          aggregate_version, received_at, applied_at
        ) VALUES (:consumerName, :messageId, :messageKind, :messageType, :messageVersion,
          :producer, :correlationId, :causationId, :operationId, :aggregateId,
          :aggregateVersion, :receivedAt, :appliedAt)
        ON CONFLICT (consumer_name, message_id) DO NOTHING
        """)
        .param("consumerName", message.consumerName())
        .param("messageId", message.messageId())
        .param("messageKind", message.messageKind())
        .param("messageType", message.messageType())
        .param("messageVersion", message.messageVersion())
        .param("producer", message.producer())
        .param("correlationId", message.correlationId())
        .param("causationId", message.causationId())
        .param("operationId", message.operationId())
        .param("aggregateId", message.aggregateId())
        .param("aggregateVersion", message.aggregateVersion())
        .param("receivedAt", message.receivedAt())
        .param("appliedAt", message.appliedAt())
        .update() == 1;
  }
}

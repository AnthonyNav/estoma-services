package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcOutboxRepositoryIT extends AbstractPostgresIT {

  @Autowired private JdbcOutboxRepository repository;
  @Autowired private JdbcClient jdbc;

  @Test
  void appendInsertsAPendingRow() {
    UUID messageId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    UUID causationId = UUID.randomUUID();
    Actor actor = new Actor("ACCOUNT", aggregateId, null, "SELF", null);

    repository.append(
        new OutboxAppendRequest(
            messageId, "AccountCreated", 1, MessageKind.EVENT.name(), aggregateId, 1, correlationId, causationId,
            operationId, "request-123", actor, "{}", Instant.now()));

    String status = jdbc.sql("SELECT status FROM outbox_messages WHERE message_id = :id").param("id", messageId).query(String.class).single();
    assertThat(status).isEqualTo("PENDING");

    OutboxRecord record = repository.claimBatch(100).stream().filter(candidate -> candidate.messageId().equals(messageId)).findFirst().orElseThrow();
    assertThat(record.operationId()).isEqualTo(operationId);
    assertThat(record.idempotencyKey()).isEqualTo("request-123");
    assertThat(record.actor()).isEqualTo(actor);
    assertThat(record.correlationId()).isEqualTo(correlationId);
    assertThat(record.causationId()).isEqualTo(causationId);
  }
}

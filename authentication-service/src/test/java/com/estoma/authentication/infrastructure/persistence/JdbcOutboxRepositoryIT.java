package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.application.port.OutboxPort;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcOutboxRepositoryIT extends AbstractPostgresIT {

  @Autowired private OutboxPort repository;
  @Autowired private JdbcClient jdbc;

  @Test
  void appendInsertsAPendingRow() {
    UUID messageId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();

    repository.append(
        new OutboxAppendRequest(
            messageId, "AccountCreated", 1, MessageKind.EVENT.name(), aggregateId, 1, UUID.randomUUID(), null, "{}", Instant.now()));

    String status = jdbc.sql("SELECT status FROM outbox_messages WHERE message_id = :id").param("id", messageId).query(String.class).single();
    assertThat(status).isEqualTo("PENDING");
  }
}

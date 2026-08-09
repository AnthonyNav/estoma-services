package com.estoma.authentication.infrastructure.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.infrastructure.persistence.JdbcOutboxRepository;
import com.estoma.authentication.testsupport.AbstractRabbitMqIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code publish-then-crash-before-mark-published} is not reproducible in
 * an integration test without actually killing the JVM mid-transaction --
 * that guarantee is documented on {@link OutboxRelay} and follows
 * mechanically from holding the claim's row lock through {@code
 * markPublished}'s commit. This class instead proves the other half of
 * "Pruebas Requeridas" (messaging-architecture.md §18): a publish that
 * fails is backed off, not silently dropped or retried forever within the
 * same tick.
 */
class OutboxRelayIT extends AbstractRabbitMqIT {

  @Autowired private JdbcOutboxRepository outboxRepository;
  @Autowired private OutboxRelay outboxRelay;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void clearOutbox() {
    jdbc.sql("DELETE FROM outbox_messages").update();
  }

  @Test
  void aRowWithNoConfiguredRoutingKeyIsBackedOffNotDropped() {
    UUID messageId = UUID.randomUUID();
    outboxRepository.append(
         new OutboxAppendRequest(
             messageId, "NoSuchEventType", 1, "EVENT", null, null, UUID.randomUUID(), null, null, null, null, null, "{}", Instant.now()));

    outboxRelay.relay();

    var row =
        jdbc.sql("SELECT status, attempts, available_at FROM outbox_messages WHERE message_id = :messageId")
            .param("messageId", messageId)
            .query((rs, rowNum) -> new Object[] {rs.getString("status"), rs.getInt("attempts"), rs.getObject("available_at", OffsetDateTime.class)})
            .single();

    assertThat(row[0]).isEqualTo("PENDING");
    assertThat(row[1]).isEqualTo(1);
    assertThat(((OffsetDateTime) row[2]).toInstant()).isAfter(Instant.now());
  }
}

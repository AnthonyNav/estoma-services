package com.estoma.communications.domain.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.contracts.InboxMessageStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxMessageTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void createdMessageStartsActive() {
    InboxMessage message = InboxMessage.create(UUID.randomUUID(), UUID.randomUUID(), "Cita cancelada", "Tu cita fue cancelada", null, NOW);

    assertThat(message.status()).isEqualTo(InboxMessageStatus.ACTIVE);
    assertThat(message.expiresAt()).isNull();
  }
}

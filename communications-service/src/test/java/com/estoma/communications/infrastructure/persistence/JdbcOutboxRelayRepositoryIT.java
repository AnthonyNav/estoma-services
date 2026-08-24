package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.OutboxAppendRequest;
import com.estoma.communications.application.port.OutboxPort;
import com.estoma.communications.contracts.Actor;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcOutboxRelayRepositoryIT extends AbstractPostgresIT {

  @Autowired private OutboxPort outbox;
  @Autowired private JdbcOutboxRelayRepository relayRepository;

  @Test
  void claimedRowsAreLeasedThenRecoverableAfterLeaseExpiry() {
    UUID messageId = UUID.randomUUID();
    Instant now = Instant.now();
    outbox.append(new OutboxAppendRequest(
        messageId, "CommunicationRequested", 1, "EVENT", UUID.randomUUID(), 1,
        UUID.randomUUID(), UUID.randomUUID(), "{}", now,
        null, null, Actor.system("communications"), null));

    var first = relayRepository.claim(10, now);
    assertThat(first).extracting(JdbcOutboxRelayRepository.OutboxRow::messageId).containsExactly(messageId);
    assertThat(relayRepository.claim(10, now.plusSeconds(30))).isEmpty();

    var recovered = relayRepository.claim(10, now.plusSeconds(61));
    assertThat(recovered).extracting(JdbcOutboxRelayRepository.OutboxRow::messageId).containsExactly(messageId);
    assertThat(recovered.getFirst().attempts()).isEqualTo(2);
  }
}

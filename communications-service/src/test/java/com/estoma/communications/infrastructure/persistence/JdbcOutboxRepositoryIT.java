package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.OutboxAppendRequest;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcOutboxRepositoryIT extends AbstractPostgresIT {

  @Autowired private JdbcOutboxRepository repository;

  @Test
  void appendSucceeds() {
    OutboxAppendRequest request =
        new OutboxAppendRequest(
            UUID.randomUUID(), "CommunicationRequested", 1, MessageKind.EVENT.name(), UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
            "{}", Instant.now());

    repository.append(request);
  }
}

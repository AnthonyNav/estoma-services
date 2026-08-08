package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.InboxPort;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcInboxRepositoryIT extends AbstractPostgresIT {

  @Autowired private InboxPort repository;

  @Test
  void firstInsertSucceedsSecondIsNoOp() {
    UUID messageId = UUID.randomUUID();

    boolean first = repository.tryInsert("authentication-service.commands", messageId, UUID.randomUUID());
    boolean second = repository.tryInsert("authentication-service.commands", messageId, UUID.randomUUID());

    assertThat(first).isTrue();
    assertThat(second).isFalse();
  }

  @Test
  void sameMessageIdUnderDifferentConsumerIsIndependent() {
    UUID messageId = UUID.randomUUID();

    boolean commands = repository.tryInsert("authentication-service.commands", messageId, UUID.randomUUID());
    boolean events = repository.tryInsert("authentication-service.events", messageId, UUID.randomUUID());

    assertThat(commands).isTrue();
    assertThat(events).isTrue();
  }
}

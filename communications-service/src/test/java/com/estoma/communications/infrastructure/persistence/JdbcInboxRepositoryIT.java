package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcInboxRepositoryIT extends AbstractPostgresIT {

  @Autowired private JdbcInboxRepository repository;

  @Test
  void firstInsertIsApplied() {
    boolean inserted = repository.tryInsert("communications-service.commands", UUID.randomUUID(), UUID.randomUUID());

    assertThat(inserted).isTrue();
  }

  @Test
  void duplicateMessageIdForSameConsumerIsIgnored() {
    String consumerName = "communications-service.commands";
    UUID messageId = UUID.randomUUID();
    repository.tryInsert(consumerName, messageId, UUID.randomUUID());

    boolean secondInsert = repository.tryInsert(consumerName, messageId, UUID.randomUUID());

    assertThat(secondInsert).isFalse();
  }

  @Test
  void sameMessageIdIsIndependentPerConsumer() {
    UUID messageId = UUID.randomUUID();
    repository.tryInsert("communications-service.recipient-projection", messageId, UUID.randomUUID());

    boolean insertedForOtherConsumer = repository.tryInsert("communications-service.appointment-cancelled-adapter", messageId, UUID.randomUUID());

    assertThat(insertedForOtherConsumer).isTrue();
  }
}

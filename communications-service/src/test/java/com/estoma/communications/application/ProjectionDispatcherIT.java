package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.contracts.consumed.AccountProjectionEventPayload;
import com.estoma.communications.contracts.consumed.ConsumedEventTypes;
import com.estoma.communications.contracts.consumed.PersonProjectionEventPayload;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import com.estoma.communications.testsupport.Envelopes;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProjectionDispatcherIT extends AbstractPostgresIT {

  @Autowired private ProjectionDispatcher dispatcher;
  @Autowired private RecipientProjectionRepository repository;

  @Test
  void accountCreatedCreatesARecipientProjectionRow() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();

    var outcome =
        dispatcher.dispatch(
            Envelopes.event(ConsumedEventTypes.ACCOUNT_CREATED, new AccountProjectionEventPayload(accountId, personId, "ACTIVE", 1), accountId, 1));

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(repository.findByAccountId(accountId)).isPresent();
  }

  @Test
  void personCreatedUpdatesAnAlreadyExistingAccountRow() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.ACCOUNT_CREATED, new AccountProjectionEventPayload(accountId, personId, "ACTIVE", 1), accountId, 1));

    var outcome =
        dispatcher.dispatch(
            Envelopes.event(
                ConsumedEventTypes.PERSON_CREATED, new PersonProjectionEventPayload(personId, "Alumno Uno", "alumno@estoma.edu", "ACTIVE", 1), personId, 1));

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(repository.findByAccountId(accountId).orElseThrow().institutionalEmail()).isEqualTo("alumno@estoma.edu");
  }

  @Test
  void redeliveringTheSameMessageIdIsADuplicate() {
    UUID accountId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    var envelope =
        Envelopes.event(
            ConsumedEventTypes.ACCOUNT_CREATED, new AccountProjectionEventPayload(accountId, UUID.randomUUID(), "ACTIVE", 1), accountId, 1, messageId);

    var first = dispatcher.dispatch(envelope);
    var second = dispatcher.dispatch(envelope);

    assertThat(first).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(second).isInstanceOf(ProjectionOutcome.Duplicate.class);
  }
}

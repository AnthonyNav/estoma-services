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
  void accountCreatedCreatesARecipientProjectionRowUsingEnvelopeVersion() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();

    var outcome = dispatcher.dispatch(
        Envelopes.event(ConsumedEventTypes.ACCOUNT_CREATED,
            new AccountProjectionEventPayload(accountId, personId, "ACTIVE"), accountId, 4));

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(repository.findByAccountId(accountId).orElseThrow().accountSourceVersion()).isEqualTo(4);
  }

  @Test
  void canonicalPersonEventsPreserveFieldsNotOwnedByEachEventShape() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.ACCOUNT_CREATED,
        new AccountProjectionEventPayload(accountId, personId, "ACTIVE"), accountId, 1));

    dispatcher.dispatch(Envelopes.event(
        ConsumedEventTypes.PERSON_CREATED,
        new PersonProjectionEventPayload(personId, "Alumno", "Uno", null, "alumno@estoma.edu", "ACTIVE"),
        personId, 1));
    dispatcher.dispatch(Envelopes.event(
        ConsumedEventTypes.PERSON_UPDATED,
        new PersonProjectionEventPayload(personId, "Alumno", "Dos", null, "nuevo@estoma.edu", null),
        personId, 2));
    dispatcher.dispatch(Envelopes.event(
        ConsumedEventTypes.PERSON_DEACTIVATED,
        new PersonProjectionEventPayload(personId, null, null, null, null, "INACTIVE"),
        personId, 3));

    var projection = repository.findByAccountId(accountId).orElseThrow();
    assertThat(projection.displayName()).isEqualTo("Alumno Dos");
    assertThat(projection.institutionalEmail()).isEqualTo("nuevo@estoma.edu");
    assertThat(projection.personStatus()).isEqualTo("INACTIVE");
    assertThat(projection.personSourceVersion()).isEqualTo(3);
  }

  @Test
  void redeliveringTheSameMessageIdIsADuplicate() {
    UUID accountId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    var envelope = Envelopes.event(
        ConsumedEventTypes.ACCOUNT_CREATED,
        new AccountProjectionEventPayload(accountId, UUID.randomUUID(), "ACTIVE"), accountId, 1, messageId);

    var first = dispatcher.dispatch(envelope);
    var second = dispatcher.dispatch(envelope);

    assertThat(first).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(second).isInstanceOf(ProjectionOutcome.Duplicate.class);
  }
}

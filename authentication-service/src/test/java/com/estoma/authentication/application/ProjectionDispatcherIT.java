package com.estoma.authentication.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.StudentNumberSyncRepository;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.consumed.ConsumedEventTypes;
import com.estoma.authentication.contracts.consumed.PersonStatusEventPayload;
import com.estoma.authentication.contracts.consumed.StudentNumberCorrectedPayload;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import com.estoma.authentication.testsupport.Envelopes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProjectionDispatcherIT extends AbstractPostgresIT {

  @Autowired private ProjectionDispatcher dispatcher;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private StudentNumberSyncRepository studentNumberSyncRepository;

  @Test
  void personCreatedInitializesTheProjection() {
    UUID personId = UUID.randomUUID();

    var outcome = dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.PERSON_CREATED, new PersonStatusEventPayload(personId, "ACTIVE", 1), personId, 1));

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(personStatusProjectionRepository.findByPersonId(personId).orElseThrow().status()).isEqualTo("ACTIVE");
  }

  @Test
  void personDeactivatedUpdatesTheProjection() {
    UUID personId = UUID.randomUUID();
    dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.PERSON_CREATED, new PersonStatusEventPayload(personId, "ACTIVE", 1), personId, 1));

    dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.PERSON_DEACTIVATED, new PersonStatusEventPayload(personId, "INACTIVE", 2), personId, 2));

    assertThat(personStatusProjectionRepository.findByPersonId(personId).orElseThrow().status()).isEqualTo("INACTIVE");
  }

  @Test
  void redeliveredEventIsADuplicate() {
    UUID personId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    var envelope = Envelopes.event(ConsumedEventTypes.PERSON_CREATED, new PersonStatusEventPayload(personId, "ACTIVE", 1), personId, 1, messageId);

    var first = dispatcher.dispatch(envelope);
    var second = dispatcher.dispatch(envelope);

    assertThat(first).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(second).isInstanceOf(ProjectionOutcome.Duplicate.class);
  }

  @Test
  void studentNumberCorrectedSynchronizesTheLoginIdentifier() {
    UUID accountId = UUID.randomUUID();
    String oldNumber = "OLD-" + UUID.randomUUID();
    String newNumber = "NEW-" + UUID.randomUUID();
    accountRepository.insert(Account.createNew(accountId, UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, oldNumber), Instant.now()));
    UUID profileId = UUID.randomUUID();

    var outcome =
        dispatcher.dispatch(
            Envelopes.event(
                ConsumedEventTypes.STUDENT_NUMBER_CORRECTED,
                new StudentNumberCorrectedPayload(accountId, profileId, 1, oldNumber, newNumber),
                accountId,
                1));

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(accountRepository.findById(accountId).orElseThrow().loginIdentifier().value()).isEqualTo(newNumber);
    assertThat(studentNumberSyncRepository.findByAccountId(accountId).orElseThrow().synchronizationStatus()).isEqualTo("SYNCHRONIZED");
  }

  @Test
  void studentNumberCorrectedFailsOnPreviousIdentifierMismatch() {
    UUID accountId = UUID.randomUUID();
    String actualNumber = "ACTUAL-" + UUID.randomUUID();
    accountRepository.insert(Account.createNew(accountId, UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, actualNumber), Instant.now()));

    dispatcher.dispatch(
        Envelopes.event(
            ConsumedEventTypes.STUDENT_NUMBER_CORRECTED,
            new StudentNumberCorrectedPayload(accountId, UUID.randomUUID(), 1, "WRONG-PREVIOUS-" + UUID.randomUUID(), "NEW-" + UUID.randomUUID()),
            accountId,
            1));

    var sync = studentNumberSyncRepository.findByAccountId(accountId).orElseThrow();
    assertThat(sync.synchronizationStatus()).isEqualTo("FAILED");
    assertThat(sync.lastErrorCode()).isEqualTo("PREVIOUS_LOGIN_IDENTIFIER_MISMATCH");
    assertThat(accountRepository.findById(accountId).orElseThrow().loginIdentifier().value()).isEqualTo(actualNumber);
  }

  @Test
  void studentNumberCorrectedDetectsAVersionGap() {
    UUID accountId = UUID.randomUUID();
    accountRepository.insert(Account.createNew(accountId, UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, "OLD-" + UUID.randomUUID()), Instant.now()));

    dispatcher.dispatch(
        Envelopes.event(ConsumedEventTypes.STUDENT_NUMBER_CORRECTED, new StudentNumberCorrectedPayload(accountId, UUID.randomUUID(), 3, "OLD123", "NEW456"), accountId, 1));

    var sync = studentNumberSyncRepository.findByAccountId(accountId).orElseThrow();
    assertThat(sync.synchronizationStatus()).isEqualTo("FAILED");
    assertThat(sync.lastErrorCode()).isEqualTo("SOURCE_VERSION_GAP");
  }
}

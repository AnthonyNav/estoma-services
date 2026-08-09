package com.estoma.authentication.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.application.port.StudentNumberSyncRepository;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.consumed.ConsumedEventTypes;
import com.estoma.authentication.contracts.consumed.PersonStatusEventPayload;
import com.estoma.authentication.contracts.consumed.StudentNumberCorrectedPayload;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.session.Session;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import com.estoma.authentication.testsupport.Envelopes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ProjectionDispatcherIT extends AbstractPostgresIT {

  @Autowired private ProjectionDispatcher dispatcher;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private StudentNumberSyncRepository studentNumberSyncRepository;
  @Autowired private JdbcClient jdbc;

  @Test
  void personCreatedInitializesTheProjection() {
    UUID personId = UUID.randomUUID();

    var outcome = dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.PERSON_CREATED, new PersonStatusEventPayload(personId, "ACTIVE", 1), personId, 1));

    assertThat(outcome).isInstanceOf(ProjectionOutcome.Applied.class);
    assertThat(personStatusProjectionRepository.findByPersonId(personId).orElseThrow().status()).isEqualTo("ACTIVE");
  }

  @Test
  void personDeactivatedOrdersByEnvelopeVersionAndRevokesAllPersonAccountSessions() {
    UUID personId = UUID.randomUUID();
    UUID firstAccountId = UUID.randomUUID();
    UUID secondAccountId = UUID.randomUUID();
    Instant now = Instant.now();
    accountRepository.insert(Account.createNew(firstAccountId, personId, LoginIdentifier.of(LoginType.USERNAME, "first-" + UUID.randomUUID()), now));
    accountRepository.insert(Account.createNew(secondAccountId, personId, LoginIdentifier.of(LoginType.USERNAME, "second-" + UUID.randomUUID()), now));
    UUID firstSessionId = UUID.randomUUID();
    UUID secondSessionId = UUID.randomUUID();
    sessionRepository.insert(Session.createNormal(firstSessionId, firstAccountId, "first-token", null, null, null, now, now.plusSeconds(60), now.plusSeconds(120)));
    sessionRepository.insert(Session.createNormal(secondSessionId, secondAccountId, "second-token", null, null, null, now, now.plusSeconds(60), now.plusSeconds(120)));

    dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.PERSON_CREATED, new PersonStatusEventPayload(personId, "ACTIVE", 99), personId, 1));

    dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.PERSON_DEACTIVATED, new PersonStatusEventPayload(personId, "INACTIVE", 1), personId, 2));

    var projection = personStatusProjectionRepository.findByPersonId(personId).orElseThrow();
    assertThat(projection.status()).isEqualTo("INACTIVE");
    assertThat(projection.sourceVersion()).isEqualTo(2);
     assertThat(sessionRepository.findById(firstSessionId).orElseThrow().revocationReason()).isEqualTo("PERSON_DEACTIVATED");
     assertThat(sessionRepository.findById(secondSessionId).orElseThrow().revocationReason()).isEqualTo("PERSON_DEACTIVATED");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'AllAccountSessionsRevoked'").query(Long.class).single()).isEqualTo(2L);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'AllAccountSessionsRevoked' AND aggregate_id IN (:first, :second)")
        .param("first", firstAccountId).param("second", secondAccountId).query(Long.class).single()).isEqualTo(2L);

    dispatcher.dispatch(Envelopes.event(ConsumedEventTypes.PERSON_DEACTIVATED, new PersonStatusEventPayload(personId, "INACTIVE", 1), personId, 1));

    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'AllAccountSessionsRevoked'").query(Long.class).single()).isEqualTo(2L);
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
    assertThat(jdbc.sql("SELECT aggregate_id, aggregate_version FROM outbox_messages WHERE message_type = 'LoginIdentifierChanged' AND payload::jsonb ->> 'accountId' = :accountId")
        .param("accountId", accountId.toString())
        .query((rs, rowNum) -> new Object[] {rs.getObject("aggregate_id", UUID.class), rs.getInt("aggregate_version")}).single())
        .containsExactly(accountId, 2);
  }

  @Test
  void studentNumberCorrectedAcceptsHigherVersionWithoutSourceGapOrPreviousIdentifierMatch() {
    UUID accountId = UUID.randomUUID();
    String actualNumber = "ACTUAL-" + UUID.randomUUID();
    accountRepository.insert(Account.createNew(accountId, UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, actualNumber), Instant.now()));
    String newNumber = "NEW-" + UUID.randomUUID();

    dispatcher.dispatch(
        Envelopes.event(
            ConsumedEventTypes.STUDENT_NUMBER_CORRECTED,
            new StudentNumberCorrectedPayload(accountId, UUID.randomUUID(), 3, "WRONG-PREVIOUS-" + UUID.randomUUID(), newNumber),
            accountId,
            1));

    var sync = studentNumberSyncRepository.findByAccountId(accountId).orElseThrow();
    assertThat(sync.synchronizationStatus()).isEqualTo("SYNCHRONIZED");
    assertThat(sync.lastAppliedStudentNumberVersion()).isEqualTo(3);
    assertThat(accountRepository.findById(accountId).orElseThrow().loginIdentifier().value()).isEqualTo(newNumber);
  }

  @Test
  void studentNumberCorrectedUsesV1ErrorCodeWhenAccountIsMissing() {
    UUID accountId = UUID.randomUUID();

    dispatcher.dispatch(
        Envelopes.event(
            ConsumedEventTypes.STUDENT_NUMBER_CORRECTED,
            new StudentNumberCorrectedPayload(accountId, UUID.randomUUID(), 1, "OLD-" + UUID.randomUUID(), "NEW-" + UUID.randomUUID()),
            accountId,
            1));

    String errorCode =
        jdbc.sql(
                "SELECT payload::jsonb ->> 'errorCode' FROM outbox_messages "
                    + "WHERE message_type = 'StudentLoginIdentifierSynchronizationFailed' "
                    + "AND payload::jsonb ->> 'accountId' = :accountId")
            .param("accountId", accountId.toString())
            .query(String.class)
            .single();
     assertThat(errorCode).isEqualTo("ACCOUNT_NOT_FOUND_FOR_STUDENT");
  }
}

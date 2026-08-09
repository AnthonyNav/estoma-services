package com.estoma.authentication.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.command.ActivateAccountCommand;
import com.estoma.authentication.contracts.command.ChangeLoginIdentifierCommand;
import com.estoma.authentication.contracts.command.CreateAccountCommand;
import com.estoma.authentication.contracts.command.DeactivateAccountCommand;
import com.estoma.authentication.contracts.command.RequestPasswordRecoveryCommand;
import com.estoma.authentication.contracts.command.RevokeAllAccountSessionsCommand;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.domain.credential.PasswordHash;
import com.estoma.authentication.domain.session.Session;
import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import com.estoma.authentication.testsupport.Envelopes;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class CommandDispatcherIT extends AbstractPostgresIT {

  @Autowired private CommandDispatcher dispatcher;
  @Autowired private AccountRepository accountRepository;
  @Autowired private CredentialRepository credentialRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;
  @Autowired private PasswordRecoveryRepository passwordRecoveryRepository;
  @Autowired private JdbcClient jdbc;
  @Autowired private EnvelopeJsonCodec codec;

  @Test
  void createAccountSucceedsForAnActivePerson() {
    UUID personId = activePerson();
    UUID accountId = UUID.randomUUID();

    var outcome =
        dispatcher.dispatch(
            Envelopes.command(
                AuthenticationCommand.CREATE_ACCOUNT.wireName(),
                new CreateAccountCommand(accountId, personId, LoginType.MATRICULA, "A" + accountId)));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(accountRepository.findById(accountId)).isPresent();
    assertThat(jdbc.sql("SELECT count(*) FROM account_status_history WHERE account_id = :accountId")
        .param("accountId", accountId)
        .query(Long.class)
        .single()).isEqualTo(1L);
  }

  @Test
  void createAccountRejectsUnknownPerson() {
    var outcome =
        dispatcher.dispatch(
            Envelopes.command(
                AuthenticationCommand.CREATE_ACCOUNT.wireName(),
                new CreateAccountCommand(UUID.randomUUID(), UUID.randomUUID(), LoginType.MATRICULA, "unknown-person")));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.PERSON_NOT_FOUND);
  }

  @Test
  void redeliveringTheSameMessageIdIsADuplicate() {
    UUID personId = activePerson();
    UUID messageId = UUID.randomUUID();
    var envelope =
        Envelopes.command(
            AuthenticationCommand.CREATE_ACCOUNT.wireName(),
            new CreateAccountCommand(UUID.randomUUID(), personId, LoginType.MATRICULA, "dup-msg-" + UUID.randomUUID()),
            messageId);

    var first = dispatcher.dispatch(envelope);
    var second = dispatcher.dispatch(envelope);

    assertThat(first).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(second).isInstanceOf(CommandOutcome.Duplicate.class);
  }

  @Test
  void differentMessageIdsWithTheSameIdempotencyKeyReplayOnlyTheTerminalResult() {
    UUID personId = activePerson();
    MessageEnvelope first = Envelopes.command(
        AuthenticationCommand.CREATE_ACCOUNT.wireName(),
        new CreateAccountCommand(UUID.randomUUID(), personId, LoginType.MATRICULA, "idem-" + UUID.randomUUID()));
    MessageEnvelope replay = withMessageId(first, UUID.randomUUID());

    assertThat(dispatcher.dispatch(first)).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(dispatcher.dispatch(replay)).isInstanceOf(CommandOutcome.Duplicate.class);

    assertThat(jdbc.sql("SELECT count(*) FROM accounts WHERE person_id = :personId")
        .param("personId", personId).query(Long.class).single()).isEqualTo(1L);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'AccountCreated' AND causation_id = :messageId")
        .param("messageId", first.messageId()).query(Long.class).single()).isEqualTo(1L);
     assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'CreateAccountResult' AND correlation_id = :correlationId")
        .param("correlationId", first.correlationId()).query(Long.class).single()).isEqualTo(2L);
    assertThat(jdbc.sql("SELECT result_data IS NOT NULL FROM command_idempotency_records WHERE idempotency_key = :key")
        .param("key", first.idempotencyKey()).query(Boolean.class).single()).isTrue();
  }

  @Test
  void differentPayloadWithTheSameIdempotencyKeyIsRejectedWithoutInvokingTheHandler() {
    UUID personId = activePerson();
    MessageEnvelope first = Envelopes.command(
        AuthenticationCommand.CREATE_ACCOUNT.wireName(),
        new CreateAccountCommand(UUID.randomUUID(), personId, LoginType.MATRICULA, "idem-" + UUID.randomUUID()));
    MessageEnvelope conflict = new MessageEnvelope(
        UUID.randomUUID(), first.messageType(), first.messageVersion(), first.messageKind(), first.producer(), first.occurredAt(),
        first.correlationId(), first.causationId(), first.operationId(), first.idempotencyKey(), first.aggregateId(), first.aggregateVersion(),
        first.actor(), codec.toJsonNode(new CreateAccountCommand(UUID.randomUUID(), personId, LoginType.MATRICULA, "other-" + UUID.randomUUID())));

    dispatcher.dispatch(first);

     assertThat(dispatcher.dispatch(conflict))
         .isEqualTo(new CommandOutcome.Rejected(RejectionCode.ACTOR_NOT_AUTHORIZED));
    assertThat(jdbc.sql("SELECT count(*) FROM accounts WHERE person_id = :personId")
        .param("personId", personId).query(Long.class).single()).isEqualTo(1L);
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'AccountCreated' AND correlation_id = :correlationId")
        .param("correlationId", first.correlationId()).query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void unauthorizedCommandDoesNotClaimIdempotencyAndStillEmitsRejectedResult() {
    UUID accountId = UUID.randomUUID();
    MessageEnvelope envelope = Envelopes.command(
        AuthenticationCommand.CREATE_ACCOUNT.wireName(),
        new CreateAccountCommand(accountId, UUID.randomUUID(), LoginType.MATRICULA, "unauthorized-" + UUID.randomUUID()));
    envelope = new MessageEnvelope(
        envelope.messageId(), envelope.messageType(), envelope.messageVersion(), envelope.messageKind(), envelope.producer(), envelope.occurredAt(),
        envelope.correlationId(), envelope.causationId(), envelope.operationId(), envelope.idempotencyKey(), envelope.aggregateId(), envelope.aggregateVersion(),
        new Actor("SYSTEM", null, "untrusted-service", "GLOBAL", null), envelope.payload());

    assertThat(dispatcher.dispatch(envelope)).isEqualTo(new CommandOutcome.Rejected(RejectionCode.ACTOR_NOT_AUTHORIZED));
    assertThat(jdbc.sql("SELECT count(*) FROM command_idempotency_records WHERE idempotency_key = :key")
        .param("key", envelope.idempotencyKey()).query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'CreateAccountResult' AND causation_id = :messageId")
        .param("messageId", envelope.messageId()).query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void activateAccountRequiresACredential() {
    UUID accountId = insertInactiveAccount(activePerson());

    var outcome =
        dispatcher.dispatch(Envelopes.command(AuthenticationCommand.ACTIVATE_ACCOUNT.wireName(), new ActivateAccountCommand(accountId, 1)));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.CREDENTIAL_NOT_CONFIGURED);
  }

  @Test
  void activateAccountSucceedsOnceCredentialConfigured() {
    UUID accountId = insertInactiveAccount(activePerson());
    credentialRepository.insert(Credential.configure(accountId, new PasswordHash("hash", "ARGON2ID", "{}"), Instant.now()));

    var outcome =
        dispatcher.dispatch(Envelopes.command(AuthenticationCommand.ACTIVATE_ACCOUNT.wireName(), new ActivateAccountCommand(accountId, 1)));

    assertThat(outcome).isEqualTo(new CommandOutcome.Succeeded(2));
    assertThat(accountRepository.findById(accountId).orElseThrow().isActive()).isTrue();
    assertThat(jdbc.sql("SELECT new_status FROM account_status_history WHERE account_id = :accountId")
        .param("accountId", accountId)
        .query(String.class)
        .single()).isEqualTo("ACTIVE");
  }

  @Test
  void deactivateAccountRevokesActiveSessions() {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    Session session = normalSession(accountId, "hash-" + UUID.randomUUID());
    sessionRepository.insert(session);

    var outcome =
        dispatcher.dispatch(
            Envelopes.command(
                AuthenticationCommand.DEACTIVATE_ACCOUNT.wireName(),
                new DeactivateAccountCommand(accountId, 2, "ADMINISTRATIVE", "manual review")));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(accountRepository.findById(accountId).orElseThrow().isActive()).isFalse();
    assertThat(sessionRepository.findById(session.sessionId()).orElseThrow().isValid(Instant.now())).isFalse();
    assertThat(accountRepository.findById(accountId).orElseThrow().deactivationReasonCode()).isEqualTo("ADMINISTRATIVE");
    assertThat(jdbc.sql("SELECT reason_code FROM account_status_history WHERE account_id = :accountId AND new_status = 'INACTIVE'")
        .param("accountId", accountId)
        .query(String.class)
        .single()).isEqualTo("ADMINISTRATIVE");
  }

  @Test
  void activationFailsClosedWhenThePersonProjectionIsMissing() {
    UUID accountId = insertInactiveAccount(UUID.randomUUID());
    credentialRepository.insert(Credential.configure(accountId, new PasswordHash("hash", "ARGON2ID", "{}"), Instant.now()));

    var outcome = dispatcher.dispatch(
        Envelopes.command(AuthenticationCommand.ACTIVATE_ACCOUNT.wireName(), new ActivateAccountCommand(accountId, 1)));

    assertThat(outcome).isEqualTo(new CommandOutcome.Rejected(RejectionCode.PERSON_NOT_FOUND));
  }

  @Test
  void studentImportMayReactivateOnlyAcademicAbsenceMatriculaAccounts() {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    dispatcher.dispatch(
        Envelopes.command(
            AuthenticationCommand.DEACTIVATE_ACCOUNT.wireName(),
            new DeactivateAccountCommand(accountId, 2, "ACADEMIC_ABSENCE", null)));

    var outcome = dispatcher.dispatch(studentImportActivation(accountId, 3));

    assertThat(outcome).isEqualTo(new CommandOutcome.Succeeded(4));
    assertThat(accountRepository.findById(accountId).orElseThrow().deactivationReasonCode()).isNull();
  }

  @Test
  void studentImportCannotReactivateAnAdministrativeDeactivation() {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    dispatcher.dispatch(
        Envelopes.command(
            AuthenticationCommand.DEACTIVATE_ACCOUNT.wireName(),
            new DeactivateAccountCommand(accountId, 2, "ADMINISTRATIVE", null)));

    var outcome = dispatcher.dispatch(studentImportActivation(accountId, 3));

    assertThat(outcome).isEqualTo(new CommandOutcome.Rejected(RejectionCode.ACTOR_NOT_AUTHORIZED));
  }

  @Test
  void changeLoginIdentifierRejectsACollision() {
    UUID personId = activePerson();
    String takenIdentifier = "taken-" + UUID.randomUUID();
     accountRepository.insert(Account.createNew(UUID.randomUUID(), personId, LoginIdentifier.of(LoginType.USERNAME, takenIdentifier), Instant.now()));
     UUID accountId = UUID.randomUUID();
     accountRepository.insert(Account.createNew(accountId, personId, LoginIdentifier.of(LoginType.USERNAME, "change-" + UUID.randomUUID()), Instant.now()));

    var outcome =
        dispatcher.dispatch(
            Envelopes.command(AuthenticationCommand.CHANGE_LOGIN_IDENTIFIER.wireName(), new ChangeLoginIdentifierCommand(accountId, 1, takenIdentifier)));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.LOGIN_IDENTIFIER_ALREADY_USED);
  }

  @Test
  void revokeAllAccountSessionsRevokesEveryValidSession() {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    sessionRepository.insert(normalSession(accountId, "s1-" + UUID.randomUUID()));

    var outcome =
        dispatcher.dispatch(
            Envelopes.command(AuthenticationCommand.REVOKE_ALL_ACCOUNT_SESSIONS.wireName(), new RevokeAllAccountSessionsCommand(accountId, "suspicious_activity")));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
  }

  @Test
  void requestPasswordRecoveryIsGenericForAnUnknownIdentifier() {
    var outcome =
        dispatcher.dispatch(
            Envelopes.command(AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName(), new RequestPasswordRecoveryCommand(LoginType.MATRICULA, "no-such-account")));

    assertThat(outcome).isEqualTo(new CommandOutcome.Succeeded(null));
  }

  @Test
  void requestPasswordRecoverySucceedsGenericallyForAKnownActiveAccount() {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    String identifier = accountRepository.findById(accountId).orElseThrow().loginIdentifier().value();

    var outcome =
        dispatcher.dispatch(
            Envelopes.command(AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName(), new RequestPasswordRecoveryCommand(LoginType.MATRICULA, identifier)));

    assertThat(outcome).isEqualTo(new CommandOutcome.Succeeded(null));
    assertThat(passwordRecoveryRepository.findActiveByAccountIdForUpdate(accountId)).isPresent();
  }

  @Test
  void requestingRecoveryDoesNotIncrementAccountVersion() {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    String identifier = accountRepository.findById(accountId).orElseThrow().loginIdentifier().value();
    int versionBefore = accountRepository.findById(accountId).orElseThrow().version();

    dispatcher.dispatch(
        Envelopes.command(AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName(), new RequestPasswordRecoveryCommand(LoginType.MATRICULA, identifier)));

    Account accountAfter = accountRepository.findById(accountId).orElseThrow();
    assertThat(accountAfter.version()).isEqualTo(versionBefore);
    assertThat(accountAfter.lastRecoveryRequestedAt()).isNotNull();
  }

  @Test
  void requestPasswordRecoveryKeepsTheActiveRequestDuringCooldown() {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    String identifier = accountRepository.findById(accountId).orElseThrow().loginIdentifier().value();

    dispatcher.dispatch(
        Envelopes.command(AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName(), new RequestPasswordRecoveryCommand(LoginType.MATRICULA, identifier)));
    UUID firstRecoveryId = passwordRecoveryRepository.findActiveByAccountIdForUpdate(accountId).orElseThrow().recoveryId();

    var outcome = dispatcher.dispatch(
        Envelopes.command(AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName(), new RequestPasswordRecoveryCommand(LoginType.MATRICULA, identifier)));

    assertThat(outcome).isEqualTo(new CommandOutcome.Succeeded(null));
    assertThat(passwordRecoveryRepository.findActiveByAccountIdForUpdate(accountId).orElseThrow().recoveryId()).isEqualTo(firstRecoveryId);
  }

  @Test
  void simultaneousInitialRecoveryRequestsCreateOnlyOneActiveRequest() throws Exception {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    String identifier = accountRepository.findById(accountId).orElseThrow().loginIdentifier().value();
    int callers = 10;
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = java.util.stream.IntStream.range(0, callers)
          .mapToObj(
              ignored ->
                  executor.submit(
                      () -> {
                        ready.countDown();
                        start.await();
                        return dispatcher.dispatch(
                            Envelopes.command(
                                AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName(),
                                new RequestPasswordRecoveryCommand(LoginType.MATRICULA, identifier)));
                      }))
          .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var future : futures) {
        assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(new CommandOutcome.Succeeded(null));
      }
    }

    assertThat(passwordRecoveryRepository.findActiveByAccountIdForUpdate(accountId)).isPresent();
    assertThat(
            jdbc.sql("SELECT count(*) FROM password_recovery_requests WHERE account_id = :accountId")
                .param("accountId", accountId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void requestPasswordRecoveryKeepsTerminalRecoveryUntouchedDuringCooldown() {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    String identifier = accountRepository.findById(accountId).orElseThrow().loginIdentifier().value();

    dispatcher.dispatch(
        Envelopes.command(
            AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName(),
            new RequestPasswordRecoveryCommand(LoginType.MATRICULA, identifier)));
    PasswordRecoveryRequest first = passwordRecoveryRepository.findActiveByAccountIdForUpdate(accountId).orElseThrow();
    passwordRecoveryRepository.markUsedIfValid(first.recoveryId(), Instant.now(), Instant.now());
    Instant requestedAt = accountRepository.findById(accountId).orElseThrow().lastRecoveryRequestedAt();

    var outcome = dispatcher.dispatch(
        Envelopes.command(
            AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName(),
            new RequestPasswordRecoveryCommand(LoginType.MATRICULA, identifier)));

    assertThat(outcome).isEqualTo(new CommandOutcome.Succeeded(null));
    assertThat(passwordRecoveryRepository.findById(first.recoveryId()).orElseThrow().status())
        .isEqualTo(PasswordRecoveryRequest.Status.USED);
    assertThat(accountRepository.findById(accountId).orElseThrow().lastRecoveryRequestedAt())
        .isEqualTo(requestedAt);
    assertThat(
            jdbc.sql("SELECT count(*) FROM password_recovery_requests WHERE account_id = :accountId")
                .param("accountId", accountId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void requestPasswordRecoveryInvalidatesAnActiveRequestAfterCooldown() {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    String identifier = accountRepository.findById(accountId).orElseThrow().loginIdentifier().value();
    PasswordRecoveryRequest previous = PasswordRecoveryRequest.createNew(
        UUID.randomUUID(), accountId, "previous-" + UUID.randomUUID(), Instant.now().minusSeconds(301), Instant.now().plusSeconds(299));
    passwordRecoveryRepository.insert(previous);

    dispatcher.dispatch(
        Envelopes.command(AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName(), new RequestPasswordRecoveryCommand(LoginType.MATRICULA, identifier)));

    assertThat(passwordRecoveryRepository.findById(previous.recoveryId()).orElseThrow().status())
        .isEqualTo(PasswordRecoveryRequest.Status.INVALIDATED);
    assertThat(passwordRecoveryRepository.findActiveByAccountIdForUpdate(accountId)).isPresent();
  }

  private UUID activePerson() {
    UUID personId = UUID.randomUUID();
    personStatusProjectionRepository.upsertIfNewer(personId, "ACTIVE", 1, Instant.now());
    return personId;
  }

  private UUID insertInactiveAccount(UUID personId) {
    Account account = Account.createNew(UUID.randomUUID(), personId, LoginIdentifier.of(LoginType.MATRICULA, "acct-" + UUID.randomUUID()), Instant.now());
    accountRepository.insert(account);
    return account.accountId();
  }

  private static Session normalSession(UUID accountId, String hash) {
    Instant now = Instant.now();
    return Session.createNormal(UUID.randomUUID(), accountId, hash, null, null, null, now, now.plusSeconds(900), now.plusSeconds(1800));
  }

  private UUID insertActiveAccountWithCredential(UUID personId) {
    UUID accountId = insertInactiveAccount(personId);
    credentialRepository.insert(Credential.configure(accountId, new PasswordHash("hash", "ARGON2ID", "{}"), Instant.now()));
    Account account = accountRepository.findById(accountId).orElseThrow();
    accountRepository.update(account.activated(Instant.now()), account.version());
    return accountId;
  }

  private MessageEnvelope studentImportActivation(UUID accountId, int expectedVersion) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        AuthenticationCommand.ACTIVATE_ACCOUNT.wireName(),
        1,
        MessageKind.COMMAND,
         "student-import-process",
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        accountId,
        null,
         new Actor("SYSTEM", null, "academic", "GLOBAL", null),
        codec.toJsonNode(new ActivateAccountCommand(accountId, expectedVersion)));
  }

  private static MessageEnvelope withMessageId(MessageEnvelope source, UUID messageId) {
    return new MessageEnvelope(
        messageId, source.messageType(), source.messageVersion(), source.messageKind(), source.producer(), source.occurredAt(),
        source.correlationId(), source.causationId(), source.operationId(), source.idempotencyKey(), source.aggregateId(),
        source.aggregateVersion(), source.actor(), source.payload());
  }
}

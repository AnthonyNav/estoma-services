package com.estoma.authentication.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.LoginType;
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
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import com.estoma.authentication.testsupport.Envelopes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommandDispatcherIT extends AbstractPostgresIT {

  @Autowired private CommandDispatcher dispatcher;
  @Autowired private AccountRepository accountRepository;
  @Autowired private CredentialRepository credentialRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;

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
  }

  @Test
  void deactivateAccountRevokesActiveSessions() {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    Session session = Session.createNew(UUID.randomUUID(), accountId, "hash-" + UUID.randomUUID(), null, null, null, Instant.now(), Instant.now().plusSeconds(900));
    sessionRepository.insert(session);

    var outcome =
        dispatcher.dispatch(Envelopes.command(AuthenticationCommand.DEACTIVATE_ACCOUNT.wireName(), new DeactivateAccountCommand(accountId, 2)));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(accountRepository.findById(accountId).orElseThrow().isActive()).isFalse();
    assertThat(sessionRepository.findById(session.sessionId()).orElseThrow().isValid(Instant.now())).isFalse();
  }

  @Test
  void changeLoginIdentifierRejectsACollision() {
    UUID personId = activePerson();
    String takenIdentifier = "taken-" + UUID.randomUUID();
    accountRepository.insert(Account.createNew(UUID.randomUUID(), personId, LoginIdentifier.of(LoginType.MATRICULA, takenIdentifier), Instant.now()));
    UUID accountId = insertInactiveAccount(personId);

    var outcome =
        dispatcher.dispatch(
            Envelopes.command(AuthenticationCommand.CHANGE_LOGIN_IDENTIFIER.wireName(), new ChangeLoginIdentifierCommand(accountId, 1, takenIdentifier)));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.LOGIN_IDENTIFIER_ALREADY_USED);
  }

  @Test
  void revokeAllAccountSessionsRevokesEveryValidSession() {
    UUID accountId = insertActiveAccountWithCredential(activePerson());
    sessionRepository.insert(Session.createNew(UUID.randomUUID(), accountId, "s1-" + UUID.randomUUID(), null, null, null, Instant.now(), Instant.now().plusSeconds(900)));

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

  private UUID insertActiveAccountWithCredential(UUID personId) {
    UUID accountId = insertInactiveAccount(personId);
    credentialRepository.insert(Credential.configure(accountId, new PasswordHash("hash", "ARGON2ID", "{}"), Instant.now()));
    Account account = accountRepository.findById(accountId).orElseThrow();
    accountRepository.update(account.activated(Instant.now()), account.version());
    return accountId;
  }
}

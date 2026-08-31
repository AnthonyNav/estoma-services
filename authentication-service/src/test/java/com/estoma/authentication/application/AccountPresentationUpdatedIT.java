package com.estoma.authentication.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.CredentialRepository;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.command.ActivateAccountCommand;
import com.estoma.authentication.contracts.command.ChangeLoginIdentifierCommand;
import com.estoma.authentication.contracts.command.CreateAccountCommand;
import com.estoma.authentication.contracts.command.DeactivateAccountCommand;
import com.estoma.authentication.domain.credential.Credential;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import com.estoma.authentication.testsupport.Envelopes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class AccountPresentationUpdatedIT extends AbstractPostgresIT {

  @Autowired private CommandDispatcher dispatcher;
  @Autowired private AccountRepository accountRepository;
  @Autowired private CredentialRepository credentialRepository;
  @Autowired private PasswordHasher passwordHasher;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private JdbcClient jdbc;

  @Test
  void appendsOneCurrentPresentationSnapshotForEveryAccountPresentationChange() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    personStatusProjectionRepository.upsertIfNewer(personId, "ACTIVE", 1, Instant.now());

    assertThat(dispatcher.dispatch(Envelopes.command(
        AuthenticationCommand.CREATE_ACCOUNT.wireName(),
        new CreateAccountCommand(accountId, personId, LoginType.USERNAME, "presentation-" + accountId))))
        .isInstanceOf(CommandOutcome.Succeeded.class);
    credentialRepository.insert(Credential.configure(accountId, passwordHasher.hash("a-long-initial-password"), Instant.now()));
    assertThat(dispatcher.dispatch(Envelopes.command(
        AuthenticationCommand.ACTIVATE_ACCOUNT.wireName(), new ActivateAccountCommand(accountId, 1))))
        .isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(dispatcher.dispatch(Envelopes.command(
        AuthenticationCommand.CHANGE_LOGIN_IDENTIFIER.wireName(),
        new ChangeLoginIdentifierCommand(accountId, 2, "corrected-" + accountId))))
        .isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(dispatcher.dispatch(Envelopes.command(
        AuthenticationCommand.DEACTIVATE_ACCOUNT.wireName(),
        new DeactivateAccountCommand(accountId, 3, "ADMINISTRATIVE", null))))
        .isInstanceOf(CommandOutcome.Succeeded.class);

    List<PresentationEvent> events = jdbc.sql(
            "SELECT aggregate_version, payload::text FROM outbox_messages "
                + "WHERE aggregate_id = :accountId AND message_type = :messageType "
                + "ORDER BY aggregate_version")
        .param("accountId", accountId)
        .param("messageType", AuthenticationEvent.ACCOUNT_PRESENTATION_UPDATED.wireName())
        .query((resultSet, rowNum) -> new PresentationEvent(
            resultSet.getInt("aggregate_version"), codec.readTree(resultSet.getString("payload"))))
        .list();

    assertThat(events).hasSize(4);
    assertThat(events).extracting(PresentationEvent::sourceVersion).containsExactly(1, 2, 3, 4);
    assertThat(events).allSatisfy(event -> {
      assertThat(event.payload().path("accountId").asString()).isEqualTo(accountId.toString());
      assertThat(event.payload().path("personId").asString()).isEqualTo(personId.toString());
      assertThat(event.payload().path("loginType").asString()).isEqualTo("USERNAME");
      assertThat(event.payload().path("loginIdentifier").asString()).isNotBlank();
      assertThat(event.payload().path("accountStatus").asString()).isNotBlank();
      assertThat(event.payload().has("password")).isFalse();
      assertThat(event.payload().has("refreshToken")).isFalse();
    });
    assertThat(events.getLast().payload().path("loginIdentifier").asString())
        .isEqualTo("corrected-" + accountId);
    assertThat(events.getLast().payload().path("accountStatus").asString()).isEqualTo("INACTIVE");
    assertThat(accountRepository.findById(accountId).orElseThrow().version()).isEqualTo(4);
  }

  private record PresentationEvent(int sourceVersion, JsonNode payload) {}
}

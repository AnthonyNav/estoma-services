package com.estoma.authentication.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.application.port.OutboxPort;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AccountStatusSnapshotQueryServiceTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final OutboxPort outbox = mock(OutboxPort.class);
  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final AccountStatusSnapshotQueryService service =
      new AccountStatusSnapshotQueryService(accounts, outbox, codec);

  @Test
  void pointSnapshotExposesOnlyAuthorizationState() {
    UUID accountId = UUID.randomUUID();
    Account account = account(accountId);
    when(accounts.findById(accountId)).thenReturn(Optional.of(account));

    service.respond(query(AccountStatusSnapshotQueryService.GET, Map.of("accountId", accountId.toString())));

    OutboxAppendRequest result = captured();
    assertThat(result.messageType()).isEqualTo("GetAccountStatusSnapshotResult");
    assertThat(result.resultRequester()).isEqualTo("authorization");
    JsonNode payload = codec.readTree(result.payloadJson());
    assertThat(payload.path("status").asString()).isEqualTo("FOUND");
    assertThat(payload.path("data").path("accountId").asString()).isEqualTo(accountId.toString());
    assertThat(payload.path("data").path("loginType").asString()).isEqualTo("USERNAME");
    assertThat(payload.path("data").has("loginIdentifier")).isFalse();
  }

  @Test
  void presentationSnapshotsExposeTheCurrentIdentifierOnlyToBff() {
    UUID accountId = UUID.randomUUID();
    Account account = account(accountId);
    when(accounts.findById(accountId)).thenReturn(Optional.of(account));

    service.respond(query(
        AccountStatusSnapshotQueryService.GET_PRESENTATION,
        "bff",
        Map.of("accountId", accountId.toString())));

    OutboxAppendRequest result = captured();
    assertThat(result.messageType()).isEqualTo("GetAccountPresentationSnapshotResult");
    assertThat(result.resultRequester()).isEqualTo("bff");
    JsonNode payload = codec.readTree(result.payloadJson());
    assertThat(payload.path("status").asString()).isEqualTo("FOUND");
    assertThat(payload.path("data").path("loginIdentifier").asString())
        .isEqualTo(account.loginIdentifier().value());
    assertThat(payload.path("data").path("accountStatus").asString()).isEqualTo("INACTIVE");
    assertThat(payload.path("data").has("status")).isFalse();
    assertThat(payload.path("data").path("sourceVersion").asInt()).isEqualTo(account.version());
  }

  @Test
  void pointSnapshotCanReturnNotFound() {
    UUID accountId = UUID.randomUUID();
    when(accounts.findById(accountId)).thenReturn(Optional.empty());

    service.respond(query(AccountStatusSnapshotQueryService.GET, Map.of("accountId", accountId.toString())));

    JsonNode payload = codec.readTree(captured().payloadJson());
    assertThat(payload.path("status").asString()).isEqualTo("NOT_FOUND");
    assertThat(payload.has("data")).isFalse();
  }

  @Test
  void pageUsesStableUuidKeyset() {
    when(accounts.listAfter(null, 201)).thenReturn(List.of(account(UUID.randomUUID())));

    service.respond(query(AccountStatusSnapshotQueryService.LIST, Map.of("limit", 200)));

    verify(accounts).listAfter(null, 201);
    JsonNode payload = codec.readTree(captured().payloadJson());
    assertThat(payload.path("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(payload.path("data").path("items").size()).isEqualTo(1);
    assertThat(payload.path("data").path("hasMore").asBoolean()).isFalse();
  }

  @Test
  void presentationPageUsesTheSameStableKeysetAndBffRouting() {
    when(accounts.listAfter(null, 201)).thenReturn(List.of(account(UUID.randomUUID())));

    service.respond(query(AccountStatusSnapshotQueryService.LIST_PRESENTATION, "bff", Map.of("limit", 200)));

    OutboxAppendRequest result = captured();
    assertThat(result.messageType()).isEqualTo("ListAccountPresentationSnapshotsPageResult");
    assertThat(result.resultRequester()).isEqualTo("bff");
    JsonNode payload = codec.readTree(result.payloadJson());
    assertThat(payload.path("data").path("items").get(0).path("loginIdentifier").asText()).isNotBlank();
  }

  @Test
  void failedQueryPersistsTerminalFailedResultForAuthorization() {
    MessageEnvelope query = query(
        AccountStatusSnapshotQueryService.GET,
        Map.of("accountId", UUID.randomUUID().toString()));

    service.fail(query, "TECHNICAL_FAILURE");

    OutboxAppendRequest result = captured();
    assertThat(result.messageType()).isEqualTo("GetAccountStatusSnapshotResult");
    assertThat(result.messageKind()).isEqualTo(MessageKind.RESULT.name());
    assertThat(result.resultRequester()).isEqualTo("authorization");
    assertThat(result.correlationId()).isEqualTo(query.correlationId());
    assertThat(result.causationId()).isEqualTo(query.messageId());
    assertThat(result.operationId()).isEqualTo(query.operationId());
    JsonNode payload = codec.readTree(result.payloadJson());
    assertThat(payload.path("status").asString()).isEqualTo("FAILED");
    assertThat(payload.path("error").path("code").asString()).isEqualTo("TECHNICAL_FAILURE");
    assertThat(payload.has("data")).isFalse();
  }

  private MessageEnvelope query(String type, Object payload) {
    return query(type, "authorization", payload);
  }

  private MessageEnvelope query(String type, String requester, Object payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.QUERY, requester, Instant.now(),
        UUID.randomUUID(), null, UUID.randomUUID(), null, null, null,
        new Actor("SYSTEM", null, requester, null, null), codec.toJsonNode(payload));
  }

  private OutboxAppendRequest captured() {
    ArgumentCaptor<OutboxAppendRequest> captor = ArgumentCaptor.forClass(OutboxAppendRequest.class);
    verify(outbox).append(captor.capture());
    return captor.getValue();
  }

  private static Account account(UUID accountId) {
    return Account.createNew(
        accountId,
        UUID.randomUUID(),
        LoginIdentifier.of(LoginType.USERNAME, "user-" + accountId),
        Instant.parse("2026-08-01T00:00:00Z"));
  }
}

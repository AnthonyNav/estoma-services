package com.estoma.authentication.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshSessionAuthorizationContractTest {

  @Test
  void refreshSessionIsPublicRpcAuthenticatedBySessionSecretNotAccessTokenActor() {
    var request = new MessageEnvelope(
        UUID.randomUUID(),
        AuthenticationRpcOperation.REFRESH_SESSION.wireName(),
        1,
        MessageKind.RPC_REQUEST,
        "bff",
        Instant.now(),
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        null,
        new Actor("ANONYMOUS", null, null, "PUBLIC", null),
        null);

    assertThat(AuthenticationAuthorizationMatrix.permits(request)).isTrue();
  }

  @Test
  void refreshSessionRejectsAccountTargetOrAccountActor() {
    UUID accountId = UUID.randomUUID();
    var withAccountTarget = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationRpcOperation.REFRESH_SESSION.wireName(), 1,
        MessageKind.RPC_REQUEST, "bff", Instant.now(), UUID.randomUUID(), null, null, null,
        accountId, null, new Actor("ANONYMOUS", null, null, "PUBLIC", null), null);
    var withAccountActor = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationRpcOperation.REFRESH_SESSION.wireName(), 1,
        MessageKind.RPC_REQUEST, "bff", Instant.now(), UUID.randomUUID(), null, null, null,
        null, null, new Actor("ACCOUNT", accountId, null, "SELF", null), null);

    assertThat(AuthenticationAuthorizationMatrix.permits(withAccountTarget)).isFalse();
    assertThat(AuthenticationAuthorizationMatrix.permits(withAccountActor)).isFalse();
  }
}

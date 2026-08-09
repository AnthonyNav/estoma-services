package com.estoma.authentication.infrastructure.messaging.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RpcListenerAuthorizationTest {

  @Test
  void rejectsAnInvalidProducer() {
    MessageEnvelope request = selfRequest(AuthenticationRpcOperation.CHANGE_PASSWORD);
    request = new MessageEnvelope(
        request.messageId(), request.messageType(), request.messageVersion(), request.messageKind(), "users-service",
        request.occurredAt(), request.correlationId(), request.causationId(), request.operationId(), request.idempotencyKey(),
        request.aggregateId(), request.aggregateVersion(), request.actor(), request.payload());

    assertThat(RpcListener.isAllowedProducer(request)).isFalse();
  }

  @Test
  void rejectsAnInvalidActor() {
    MessageEnvelope request = selfRequest(AuthenticationRpcOperation.CHANGE_PASSWORD);
    request = new MessageEnvelope(
        request.messageId(), request.messageType(), request.messageVersion(), request.messageKind(), request.producer(),
        request.occurredAt(), request.correlationId(), request.causationId(), request.operationId(), request.idempotencyKey(),
        request.aggregateId(), request.aggregateVersion(), new Actor("SYSTEM", null, "bff", "SELF", null), request.payload());

    assertThat(RpcListener.isAllowedProducer(request)).isFalse();
  }

  @Test
  void rejectsAnInvalidScope() {
    MessageEnvelope request = selfRequest(AuthenticationRpcOperation.CHANGE_PASSWORD);
    request = new MessageEnvelope(
        request.messageId(), request.messageType(), request.messageVersion(), request.messageKind(), request.producer(),
        request.occurredAt(), request.correlationId(), request.causationId(), request.operationId(), request.idempotencyKey(),
        request.aggregateId(), request.aggregateVersion(), new Actor("ACCOUNT", request.aggregateId(), null, "GLOBAL", null), request.payload());

    assertThat(RpcListener.isAllowedProducer(request)).isFalse();
  }

  @Test
  void permitsRecoveryCodeOnlyForCommunicationsSystemIdentity() {
    MessageEnvelope request = new MessageEnvelope(
        java.util.UUID.randomUUID(), AuthenticationRpcOperation.GET_RECOVERY_CODE_FOR_DELIVERY.wireName(), 1,
        MessageKind.RPC_REQUEST, "communications", Instant.now(), java.util.UUID.randomUUID(), null, null, null, null,
        null, new Actor("SYSTEM", null, "communications", "GLOBAL", null), null);

    assertThat(RpcListener.isAllowedProducer(request)).isTrue();
    request = new MessageEnvelope(
        request.messageId(), request.messageType(), request.messageVersion(), request.messageKind(), "bff", request.occurredAt(),
        request.correlationId(), request.causationId(), request.operationId(), request.idempotencyKey(), request.aggregateId(),
        request.aggregateVersion(), request.actor(), request.payload());
    assertThat(RpcListener.isAllowedProducer(request)).isFalse();
  }

  private static MessageEnvelope selfRequest(AuthenticationRpcOperation operation) {
    java.util.UUID accountId = java.util.UUID.randomUUID();
    return new MessageEnvelope(
        java.util.UUID.randomUUID(), operation.wireName(), 1, MessageKind.RPC_REQUEST, "bff", Instant.now(),
        java.util.UUID.randomUUID(), null, null, null, accountId, null, new Actor("ACCOUNT", accountId, null, "SELF", null), null);
  }
}

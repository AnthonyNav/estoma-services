package com.estoma.authentication.contracts.rpc;

import com.estoma.authentication.contracts.Actor;
import java.util.UUID;

/** Transport-owned authorization and correlation data for an RPC invocation. */
public record RpcRequestContext(
    UUID messageId,
    UUID correlationId,
    String producer,
    Actor actor,
    UUID targetAccountId,
    String idempotencyKey) {

  public static RpcRequestContext from(com.estoma.authentication.contracts.MessageEnvelope envelope) {
    return new RpcRequestContext(
        envelope.messageId(),
        envelope.correlationId(),
        envelope.producer(),
        envelope.actor(),
        envelope.aggregateId(),
        envelope.idempotencyKey());
  }
}

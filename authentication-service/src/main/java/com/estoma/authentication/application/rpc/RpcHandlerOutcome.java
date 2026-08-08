package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.EventToAppend;
import java.util.List;
import java.util.UUID;

/**
 * What a successful RPC handler produces: the response to return directly
 * (never persisted -- system_contract.md "RPC Security Rules"), plus zero
 * or more non-sensitive events to append to the Outbox in the same
 * transaction. {@code aggregateId}/{@code aggregateVersion} may be null for
 * operations with no natural aggregate correlation (none of the current
 * RPC operations need this, but the shape stays consistent with {@code
 * CommandHandlerResult}).
 */
public record RpcHandlerOutcome<T>(T response, UUID aggregateId, Integer aggregateVersion, List<EventToAppend> events) {

  public static <T> RpcHandlerOutcome<T> withoutEvents(T response) {
    return new RpcHandlerOutcome<>(response, null, null, List.of());
  }

  public static <T> RpcHandlerOutcome<T> of(T response, UUID aggregateId, Integer aggregateVersion, EventToAppend... events) {
    return new RpcHandlerOutcome<>(response, aggregateId, aggregateVersion, List.of(events));
  }
}

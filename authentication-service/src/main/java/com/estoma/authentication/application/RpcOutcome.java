package com.estoma.authentication.application;

import com.estoma.authentication.domain.RejectionCode;

/**
 * What {@link RpcDispatcher#execute} concluded. Unlike {@link
 * CommandOutcome}, there is no {@code Duplicate} variant: ephemeral RPC
 * never uses Consumer Inbox (system_contract.md "RPC Security Rules").
 */
public sealed interface RpcOutcome<T> {

  record Success<T>(T response) implements RpcOutcome<T> {}

  record Rejected<T>(RejectionCode code) implements RpcOutcome<T> {}
}

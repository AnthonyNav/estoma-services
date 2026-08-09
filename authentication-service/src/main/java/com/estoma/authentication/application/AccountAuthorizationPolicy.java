package com.estoma.authentication.application;

import com.estoma.authentication.contracts.MessageEnvelope;

/**
 * Authorization for durable commands and RPC operations against an
 * account. Checks must use the envelope/request's {@link Actor}, never data
 * taken from the payload (security-observability.md:156).
 */
public interface AccountAuthorizationPolicy {

  void check(MessageEnvelope envelope);
}

package com.estoma.authentication.contracts;

/**
 * The V1 message kinds {@link MessageEnvelope} may carry. Security RPC uses
 * {@code RPC_REQUEST}/{@code RPC_RESPONSE} over Direct Reply-To; it remains
 * ephemeral because it never enters the durable messaging flows.
 */
public enum MessageKind {
  COMMAND,
  RESULT,
  EVENT,
  RPC_REQUEST,
  RPC_RESPONSE
}

/**
 * Request/response records for ephemeral security RPC operations
 * (platform.security.rpc / rpc.authentication / Direct Reply-To). These
 * never travel inside a {@link com.estoma.authentication.contracts.MessageEnvelope}:
 * no operationId, no durable Inbox/Outbox/DLQ, correlated only by
 * correlationId (system_contract.md "RPC Security Rules").
 */
package com.estoma.authentication.contracts.rpc;

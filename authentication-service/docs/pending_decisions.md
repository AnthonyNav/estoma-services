# authentication-service Pending Decisions

## Purpose

This file tracks decisions that are not safe to infer during implementation. A future PR must either resolve an item with a documented decision or keep the behavior out of scope.

## Security And Credentials

- Password hash algorithm.
- Password hash parameters: cost, memory, iterations, output length, and upgrade strategy.
- Password policy: minimum length, complexity, reuse rules, and blocked values.
- Temporary password format.
- Temporary password expiration.
- Whether temporary password usage revokes previous sessions immediately or after password change.
- Policy for `mustChangePassword` after temporary login.

## Sessions And Tokens

- Access token format.
- Signing algorithm and key management.
- Access token lifetime.
- Refresh token lifetime.
- Refresh token rotation strategy.
- Grace window, if any, after a lost refresh response.
- Maximum concurrent sessions per account.
- Session device metadata to store.
- Session retention after expiration or revocation.
- Revocation policy after password change.

## Lockout And Rate Limits

- Failed login attempt limit.
- Lock duration.
- Whether lock duration escalates after repeated failures.
- Recovery-code attempt limit.
- RPC and command rate limit policy.

## Password Recovery

- Recovery token or code format.
- Recovery expiration.
- Secure delivery strategy for recovery secret.
- Exact contract with `communications-service` for recovery.
- Whether password change notifications are required.
- Retention for recovery requests.

## Messaging Contracts

- Concrete routing keys for commands, results, events, and RPC.
- Final command envelopes.
- Final result envelopes.
- Final RPC request and response payloads.
- Final event versions and payload details.
- Producer permissions by operation.
- Consumer permissions by queue.
- RabbitMQ prefetch, retry, and DLQ policy for durable consumers.
- TTL and timeout values for ephemeral security RPC.

## Database And Consistency

- Physical migration tool and conventions.
- Isolation level per critical operation.
- Locking strategy for refresh token rotation.
- Outbox Relay claim strategy.
- Outbox retry policy.
- Inbox retention policy.
- Retention for `security_rpc_idempotency_records`.
- Retention for sessions, audit records, and events.

## Observability And Audit

- Audit storage mechanism.
- Audit retention.
- Security event detail allowed for failed authentication.
- Metrics names and labels.
- Log redaction rules and implementation library.
- Trace propagation conventions.

## Deployment

- Resource requests and limits.
- HPA metrics.
- Secret management strategy beyond raw Kubernetes Secrets.
- Database user and database creation workflow.
- Environment-specific RabbitMQ topology ownership.

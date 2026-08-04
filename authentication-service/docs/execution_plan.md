# authentication-service Execution Plan

## Goal

Deliver `authentication-service` safely through small pull requests into `develop`. Each PR must only implement behavior that is already defined or explicitly document what remains pending.

## Branch And PR Policy

- All changes target `develop` through pull requests.
- No direct commits to `develop`.
- Each PR must keep secrets out of Git.
- Each PR must include verification notes.
- If a decision is still open, document it in `docs/pending_decisions.md` instead of guessing.

## PR1: Scaffold And System Contract

Scope:

- keep the Spring Boot service scaffold minimal;
- add `docs/system_contract.md`;
- add this execution plan;
- add `docs/pending_decisions.md`;
- add a migrations placeholder if needed;
- add operational health endpoints for startup, readiness, and liveness;
- add canonical enum/constant names for defined commands, RPC operations, events, login types, account statuses, exchanges, and queues;
- update the service README with documentation links.

Out of scope:

- authentication logic;
- password hashing;
- token generation;
- RabbitMQ consumers;
- RabbitMQ publishers;
- database migrations with real tables;
- Outbox Relay implementation.

Acceptance criteria:

- service builds and tests continue passing;
- docs describe the system boundary and future phases;
- code only exposes safe foundation constants and operational endpoints;
- pending decisions remain explicit.

## PR2: Runtime Infrastructure Shell

Scope:

- typed configuration for service, database, RabbitMQ, observability, and security knobs;
- startup, readiness, liveness, and metrics behavior aligned with deployment expectations;
- safe logging baseline with sensitive-field redaction rules;
- Docker/runtime verification.

Out of scope:

- functional command handlers;
- authentication flows;
- credential storage.

## PR3: Database Migrations

Scope:

- create the initial `authentication-db` schema;
- add tables documented in the system contract;
- add minimum constraints and indexes for uniqueness and idempotence;
- add migration tests or validation command.

Out of scope:

- business command processing;
- token and password algorithms unless already approved.

## PR4: Messaging Shell

Scope:

- durable command consumer for `commands.authentication-service`;
- ephemeral RPC consumer for `rpc.authentication`;
- result publisher for `platform.results`;
- Outbox publisher skeleton;
- Consumer Inbox for durable events;
- contract types or envelopes without functional handlers.

Out of scope:

- credential verification;
- token generation;
- password reset behavior.

## PR5: Person Projection Consumers

Scope:

- consume `PersonCreated`;
- consume `PersonActivated`;
- consume `PersonDeactivated`;
- update `person_status_projection` idempotently;
- prevent future authentication for inactive persons once authentication is implemented.

Out of scope:

- account administration flows;
- session creation.

## PR6: Durable Account Administration

Scope:

- `CreateAccount`;
- `ActivateAccount`;
- `DeactivateAccount`;
- `ChangeLoginIdentifier`;
- `RevokeAllAccountSessions`;
- correlated results through `platform.results`;
- non-sensitive events through Outbox.

Out of scope:

- passwords in durable commands;
- login or refresh;
- recovery codes.

## PR7: Administrative Temporary Password RPC

Scope:

- `SetTemporaryPassword`;
- `RegenerateTemporaryPassword`;
- `security_rpc_idempotency_records` behavior;
- Direct Reply-To response with one-time generated secret;
- no secret persistence in plain text;
- `TemporaryPasswordSet`, `TemporaryPasswordRegenerated`, and session revocation events when applicable.

Prerequisites:

- password hash algorithm and parameters approved;
- temporary password policy approved.

## PR8: Login, Refresh, And Logout

Scope:

- `AuthenticateAccount`;
- `RefreshSession`;
- `RevokeSession`;
- failed attempts;
- temporary account locks;
- session persistence;
- token generation and refresh-token handling.

Prerequisites:

- token format approved;
- refresh token rotation policy approved;
- session duration approved;
- lockout policy approved.

## PR9: Password Recovery

Scope:

- `RequestPasswordRecovery`;
- `ValidateRecoveryCode`;
- `ResetPassword`;
- recovery token/code hashing and single-use behavior;
- integration event for `communications-service` without publishing the full secret;
- session revocation policy after reset.

Prerequisites:

- recovery delivery strategy approved;
- recovery expiration approved;
- communication contract approved.

## PR10: Student Number Synchronization

Scope:

- consume `StudentNumberCorrected`;
- apply ordered synchronization through `student_number_login_sync_projection`;
- publish `LoginIdentifierChanged` or `StudentLoginIdentifierSynchronizationFailed`.

Prerequisites:

- final event payload from `academic-service` confirmed;
- conflict handling policy reviewed.

## Safe Delivery Rules

- Do not introduce secrets in durable messages.
- Do not implement sensitive flows before their policies are approved.
- Prefer database constraints over in-memory checks for critical invariants.
- Keep each PR independently reviewable.
- Update this plan when a phase changes.

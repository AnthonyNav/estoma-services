# Phase 3 PR Scope

Included: canonical RabbitMQ command/event consumers, retry/DLQ, publisher confirms, Outbox Relay, claim recovery, readiness, local RabbitMQ, and messaging regression coverage.

Excluded: `PasswordRecoveryRequested` and `GetRecoveryCodeForDelivery`; they are delivered together in the secure-recovery PR so the sensitive workflow is never partially enabled.

Excluded: all `estoma-infra` changes.

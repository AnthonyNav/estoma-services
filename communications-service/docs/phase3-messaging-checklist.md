# Phase 3 Messaging Verification Checklist

- [x] canonical command queue: `commands.communications`
- [x] one durable event queue: `events.communications`
- [x] manual ACK after transactional dispatcher returns
- [x] bounded retry tiers and DLQ for Commands and Events
- [x] consumed Event retry-return stays on `platform.dlx`
- [x] owner-producer and aggregate identity validation before projection mutation
- [x] Transactional Outbox retains correlation, causation, operation, idempotency, actor and Result destination metadata
- [x] Outbox Relay requires publisher confirmation before marking `PUBLISHED`
- [x] Results require routability; Events require broker confirmation
- [x] recoverable `PROCESSING` claims with `FOR UPDATE SKIP LOCKED`
- [x] commandConsumer/outboxRelay readiness contributors
- [x] local RabbitMQ compose dependency
- [x] retry/routing/ownership/claim recovery regression coverage added
- [ ] CI execution: unavailable while the repository account Actions quota is exhausted

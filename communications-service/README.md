# communications-service

Spring Boot service that delivers platform communications (internal inbox and email) requested by other domains.

## Current Scope

All business logic is implemented and tested against a real database: the full schema, the domain aggregates, the five durable commands (`RequestCommunication`, `RegisterInboxVisit`, `RetryEmailDelivery`, `ResolveIndeterminateEmailDelivery`, `ExpireInboxMessages`), the `recipient_projection` consumers, the `AppointmentCancelled` adapter, and the email delivery worker. `ResolveIndeterminateEmailDelivery` requires a real indeterminate attempt for acceptance and explicit duplicate-risk acknowledgement for resend. Everything runs by invoking the transactional dispatchers directly — there is no RabbitMQ consumer or publisher yet; that is Phase 3 (see `docs/execution_plan.md`).

## Service Docs

- `docs/system_contract.md`: implementation contract for the service boundary.
- `docs/execution_plan.md`: phased PR plan for safe delivery.
- `docs/pending_decisions.md`: decisions that must remain explicit before implementation.

## Local Development

```bash
docker compose up -d
./mvnw spring-boot:run
```

No environment variables are required; `docker-compose.yml` matches the defaults baked into `application.yml`.

## Owns

- The `Communication` aggregate: origin, recipient, system, type, priority, and status.
- Internal inbox messages and visits.
- Email deliveries and their delivery attempts.
- Provider selection, fallback, and retry orchestration for email.
- A `recipient_projection` derived from Person and Account events.

## Does Not Own

- Business decisions that trigger a communication (the requesting domain decides *when*; this service decides *how* to deliver).
- Identity, account, or session data.

## Operational Endpoints

Served by Actuator health groups, not a custom controller (`management.endpoints.web.base-path` is `/`, so these are the real paths, not `/actuator/*`):

- `GET /health`
- `GET /health/startup`
- `GET /health/readiness`
- `GET /health/liveness`
- `GET /metrics`
- `GET /info`

Everything else is denied by `HttpSecurityConfig`.

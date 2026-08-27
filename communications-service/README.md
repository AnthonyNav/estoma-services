# communications-service

Spring Boot service that delivers platform communications (internal inbox and email) requested by other domains.

## Current Scope

The service already contains the domain/application behavior, PostgreSQL persistence, durable RabbitMQ command/event wiring, Transactional Outbox, recipient projections, confirmed business adapters, secure password-recovery delivery flow, and the email delivery worker with retry/lease/fencing semantics.

The email provider boundary is implemented through the neutral `EmailProviderPort`, but the only concrete provider currently present is `StubEmailProvider`. AWS SES API v2 has now been selected as the first real PRIMARY provider; the required implementation work is documented in `docs/aws-ses-adapter-plan.md`.

The SES integration must preserve the existing hexagonal boundary: AWS SDK types, exceptions and configuration belong only to infrastructure and must not leak into domain, application or contracts.

## Service Docs

- `docs/system_contract.md`: implementation contract for the service boundary.
- `docs/execution_plan.md`: phased PR plan used to deliver the service foundation and messaging integration.
- `docs/aws-ses-adapter-plan.md`: concrete changes required to add AWS SES behind `EmailProviderPort` without provider coupling.
- `docs/pending_decisions.md`: decisions that remain genuinely open after selecting SES as PRIMARY.

## Local Development

```bash
docker compose up -d
./mvnw spring-boot:run
```

Local/test execution continues to use `StubEmailProvider` unless a real provider is explicitly configured in a future implementation PR. No AWS credentials are required for normal local development or CI tests.

## Owns

- The `Communication` aggregate: origin, recipient, system, type, priority, and status.
- Internal inbox messages and visits.
- Email deliveries and their delivery attempts.
- Provider selection, fallback eligibility, and retry orchestration for email.
- Recipient projections derived from Person and Account events.

## Does Not Own

- Business decisions that trigger a communication (the requesting domain decides *when*; this service decides *how* to deliver).
- Identity, account, or session data.
- AWS IAM credentials, SES identity/domain verification, DKIM/DNS, production-access requests or provider quota administration.

## Provider Boundary

The intended real-provider path is:

```text
EmailDeliveryWorker
        ↓
EmailProviderRouter
        ↓
EmailProviderPort                  # application-owned neutral port
        ↓
SesEmailProvider                  # infrastructure adapter to implement
        ↓
AWS SES API v2
```

`StubEmailProvider` remains a deterministic local/test adapter and is not considered a production fallback provider.

## Operational Endpoints

Served by Actuator health groups, not a custom controller (`management.endpoints.web.base-path` is `/`, so these are the real paths, not `/actuator/*`):

- `GET /health`
- `GET /health/startup`
- `GET /health/readiness`
- `GET /health/liveness`
- `GET /metrics`
- `GET /info`

Everything else is denied by `HttpSecurityConfig`.

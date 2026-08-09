# authentication-service

Spring Boot service that owns access accounts, credentials, sessions, login
lockout, password recovery and temporary passwords. It exposes no client
business HTTP API: functional input is RabbitMQ only.

## V1 Contract

The platform documentation at
`03-services/shared/authentication-service/` is normative. The service:

- consumes durable Commands on `platform.commands` / `commands.authentication`;
- consumes security RPC on `platform.security.rpc` / `rpc.authentication`;
- returns RPC responses using Direct Reply-To;
- publishes non-sensitive Results and events through Transactional Outbox;
- never persists or logs passwords, hashes, refresh tokens, recovery codes or
  generated temporary passwords in messaging infrastructure.

## Operational Endpoints

- `GET /health`
- `GET /health/startup`
- `GET /health/readiness`
- `GET /health/liveness`
- `GET /metrics`
- `GET /info`

All other HTTP endpoints are denied.

## Local Development

```bash
docker compose up -d
./mvnw clean verify
```

Ephemeral JWT keys are permitted only for local and test profiles. Staging and
production require a configured private key, public key, `kid`, issuer and
audience supplied through deployment secrets/configuration.

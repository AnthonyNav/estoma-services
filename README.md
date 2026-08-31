# estoma-services

Monorepo for Estoma microservices.

## Repository Conventions

- Each microservice lives in a top-level directory.
- No shared `libs/` or cross-service imports.
- Each microservice owns its dependencies, tests, and Dockerfile.
- Spring Boot services use Java 25 and keep their own Maven Wrapper inside the service directory.
- GitHub remains the forge and GitHub Actions remain the active CI until Woodpecker parity is demonstrated.
- Woodpecker pilot workflows live under `.woodpecker/` and are scoped by path.
- Deployment manifests live in `estoma-infra`, not in this repository.

## Branches

- `develop`: integration branch. During the CI migration, only services explicitly cut over to Woodpecker may publish/promote from Woodpecker; the rest keep their existing GitHub Actions behavior.
- `main`: stable production-ready code. Pushes here build stable images but do not deploy directly to production.
- `feature/<service>/<description>`: feature work.
- `fix/<service>/<description>`: bug fixes.

`estoma-services/main` represents production-ready source code. Production deployment is still controlled from `estoma-infra` by promoting a built image tag to the `production` overlay.

## Services

- `infra-test`: minimal Spring Boot service used to validate CI, GHCR, GitOps, Traefik, and K3s.
- `deploy-test`: minimal Spring Boot service used to validate path-scoped CI/CD and GitOps automation.
- `users-service`: personal identity service.
- `authentication-service`: accounts, credentials, and sessions service.
- `authorization-service`: roles, permissions, and system access service.
- `academic-service`: student academic information service.
- `communications-service`: email, templates, inbox, and notification delivery service.
- `wash-administration-service`: Lavado Ultrasonico schedule and resource configuration service.
- `appointments-service`: Lavado Ultrasonico appointments, QR, capacity, and points service.
- `wash-execution-service`: Lavado Ultrasonico arrival, assignment, incident, and exit service.
- `jornadas-service`: Prácticas Profesionales Jornadas catalog — publish, edit, cancel, and automatic date-based finalization.
- `platform-bff`: client-facing API gateway and composition layer.

## Local Development

Run tests for one service:

```bash
cd <service-name>
./mvnw -B clean verify
```

Run a service locally:

```bash
cd <service-name>
./mvnw spring-boot:run
```

Build a service container from the repository root:

```bash
docker build -t <service-name> ./<service-name>
```

## CI/CD Automation

The target delivery path is:

```text
GitHub
-> Woodpecker CI
-> GHCR
-> estoma-infra Pull Request
-> Argo CD
-> K3s
```

The migration is incremental. GitHub Actions are removed only after the equivalent Woodpecker gate has executed successfully on a real commit.

### Current Woodpecker pilot

Only `deploy-test` is allowed to exercise the Woodpecker publishing path initially:

1. `deploy-test-test` runs `./mvnw -B clean verify` for pull requests and pushes.
2. `deploy-test-build` uses `woodpeckerci/plugin-kaniko:2.3.2` to publish `develop-<short-sha>` / `main-<short-sha>` plus the convenience branch tag without Docker-in-Docker or a host Docker socket.
3. On `develop`, `deploy-test-gitops` creates and squash-merges a deterministic Pull Request that changes only `apps/deploy-test/overlays/develop/kustomization.yaml` in `estoma-infra`.
4. Argo CD deploys the merged immutable image automatically. Staging and production remain explicit promotions.

Woodpecker repository secrets are provisioned in Woodpecker, never committed here:

- `GHCR_USERNAME` and `GHCR_TOKEN` are available only to trusted push publication workflows.
- `ESTOMA_INFRA_TOKEN` is available only to the trusted `develop` GitOps workflow and is limited to `contents: write` plus `pull-requests: write` on `AnthonyNav/estoma-infra`.

Pull-request workflows receive no publishing credentials. Woodpecker receives no kubeconfig and does not execute `kubectl apply`.

Before activating the `deploy-test` publisher in Woodpecker, disable the equivalent GitHub Actions publisher for `deploy-test`. Do not run both publishers for the same deployment intent concurrently.

After the pilot proves tests, GHCR publication, infrastructure PR creation, Argo CD `Synced Healthy`, and deployed smoke validation, migrate services one by one in the order defined by the infrastructure migration runbook.

Pushes to `main` build stable candidates but do not automatically update `staging` or `production` overlays. Those environments are promoted explicitly from `estoma-infra`.

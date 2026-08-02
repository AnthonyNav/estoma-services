# estoma-services

Monorepo for Estoma microservices.

## Repository Conventions

- Each microservice lives in a top-level directory.
- No shared `libs/` or cross-service imports.
- Each microservice owns its dependencies, tests, and Dockerfile.
- Services use Java 25, Spring Boot 4.1.x, and Maven Wrapper.
- GitHub Actions live under `.github/workflows/` and are scoped by path.
- Deployment manifests live in `estoma-infra`, not in this repository.

## Branches

- `develop`: integration branch. Pushes here build changed service images and automatically update the matching `develop` overlay in `estoma-infra`.
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
- `platform-bff`: client-facing API gateway and composition layer.

The domain services and BFF currently have Spring Boot code skeletons only. Add path-scoped workflows and GitOps manifests when each service is ready to deploy.

## Local Development

Run tests for one service from the repository root:

```bash
./mvnw -B -f <service-name>/pom.xml clean verify
```

Run a service locally:

```bash
./mvnw -f <service-name>/pom.xml spring-boot:run
```

Build a service container from the repository root:

```bash
docker build -f <service-name>/Dockerfile -t <service-name> .
```

## CI/CD Automation

Service workflows are scoped by path. A change under one service directory should only trigger that service workflow.

On pushes to `develop`, each service workflow:

1. Runs that service's tests.
2. Builds and pushes only that service image to GHCR.
3. Updates only that service's `develop` overlay tag in `estoma-infra`.
4. Lets Argo CD deploy the updated image.

The cross-repository update requires repository secret `ESTOMA_INFRA_TOKEN` in this repository. The token must allow `contents: write` on `AnthonyNav/estoma-infra`.

Pushes to `main` build stable images but do not automatically update `staging` or `production` overlays. Those environments should be promoted explicitly from `estoma-infra`.

Recommended promotion flow:

1. Merge service changes into `develop`.
2. Let CI deploy the changed service automatically to the `develop` overlay in `estoma-infra`.
3. Validate the service in the `develop` environment.
4. Merge the stable service code into `main`.
5. Build a `main-<sha>` image from `estoma-services/main`.
6. Promote that immutable image tag through `staging` and `production` by changing overlays in `estoma-infra`.

# estoma-services

Monorepo for Estoma microservices.

## Repository Conventions

- Each microservice lives in a top-level directory.
- No shared `libs/` or cross-service imports.
- Each microservice owns its dependencies, tests, and Dockerfile.
- GitHub Actions live under `.github/workflows/` and are scoped by path.
- Deployment manifests live in `estoma-infra`, not in this repository.

## Branches

- `main`: stable code.
- `develop`: integration branch for the `develop` environment.
- `feature/<service>/<description>`: feature work.
- `fix/<service>/<description>`: bug fixes.

## Services

- `infra-test`: minimal FastAPI service used to validate CI, GHCR, GitOps, Traefik, and K3s.
- `deploy-test`: minimal FastAPI service used to validate path-scoped CI/CD and GitOps automation.

## CI/CD Automation

Service workflows are scoped by path. A change under one service directory should only trigger that service workflow.

On pushes to `develop`, each service workflow:

1. Runs that service's tests.
2. Builds and pushes only that service image to GHCR.
3. Updates only that service's `develop` overlay tag in `estoma-infra`.
4. Lets Argo CD deploy the updated image.

The cross-repository update requires repository secret `ESTOMA_INFRA_TOKEN` in this repository. The token must allow `contents: write` on `AnthonyNav/estoma-infra`.

Pushes to `main` build images but do not automatically update `staging` or `production` overlays. Those environments should be promoted explicitly from `estoma-infra`.

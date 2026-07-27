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

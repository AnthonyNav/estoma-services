# Platform BFF E2E harness

The harness has two deliberately different collections:

- `postman/platform-bff-e2e.postman_collection.json` is the safe smoke suite: health, runtime OpenAPI and unauthenticated boundary checks. It can be used against a local BFF or an approved `develop` endpoint.
- `postman/platform-bff-api.postman_collection.json` is a generated-style catalogue of the current 85 public OpenAPI operations. It is **not** a workflow suite and contains state-changing requests; run the static contract gate rather than running it blindly.
- `postman/workflows/` contains fixture-backed Front V1 workflows only after their contracts have been exercised. Authentication coverage and its fixture requirements are documented in `postman/workflows/README.md`.

## Static contract gate

Run without contacting any service:

```sh
node e2e/scripts/validate-openapi-postman.mjs
node e2e/scripts/validate-auth-workflow.mjs
```

The gate requires an exact method/path inventory and checks `Idempotency-Key` and bearer-security requirements for every operation. `docs/openapi.yaml` is JSON-formatted OpenAPI despite its filename.

## Local smoke

Build the pinned Newman image and run it against a local BFF only after the BFF is already running:

```sh
docker build -f e2e/runner/Dockerfile -t platform-bff-e2e:local e2e
docker run --rm -e BFF_BASE_URL=http://host.docker.internal:18080 -v "$PWD/target/e2e:/reports" platform-bff-e2e:local
```

The runner writes `junit.xml` and `newman.json` beneath the mounted reports directory. The example environments contain blanks only; provide credentials at runtime only when a future workflow requires them.

## Real `develop`

The smoke collection may be run against `develop` only from an approved network location and after its deployment is `Synced/Healthy`. This repository does not claim that such an E2E run has occurred. Do not run the catalogue against `develop`: it includes mutating operations. The future workflow suite must use authorized synthetic fixtures, unique runtime values and explicit cleanup through business commands.

No tokens, passwords, personal data, fixture UUIDs or response captures belong in Git. Keep generated reports under `target/e2e/`, which is ignored by Maven conventions.

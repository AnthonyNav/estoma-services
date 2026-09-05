# Platform BFF E2E harness

The harness has two deliberately different collections:

- `postman/platform-bff-e2e.postman_collection.json` is the safe smoke suite: health, runtime OpenAPI and unauthenticated boundary checks. It can be used against a local BFF or an approved `develop` endpoint.
- `postman/platform-bff-api.postman_collection.json` is a generated-style catalogue of the current 87 public OpenAPI operations. It is **not** a workflow suite and contains state-changing requests; run the static contract gate rather than running it blindly.
- `postman/workflows/` contains explicitly ordered, fixture-backed Front V1 workflows. Each workflow documents its own deployment and live-certification state.

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

Validate the isolated student workflow without contacting a service:

```sh
node e2e/scripts/validate-wash-student-workflow.mjs
```

After its availability dependencies are deployed, run the mutating WASH-STU workflow only through
its guarded wrapper:

```sh
BFF_BASE_URL="$RUNTIME_BFF_URL" \
E2E_LOGIN_IDENTIFIER="$RUNTIME_E2E_LOGIN" \
E2E_PASSWORD="$RUNTIME_E2E_PASSWORD" \
E2E_PSQL_EXEC="$PRIVATE_PSQL_EXEC" \
./e2e/runner/run-wash-student.sh
```

The wrapper passes credentials through an ephemeral mode-`0600` Postman environment and does not
retain Newman's JSON reporter because it can capture authenticated request bodies or tokens. Newman
exports only the runtime environment into a host-owned, mode-`0600` file in a private temporary
directory so the exit trap can revoke an authenticated session even after `--bail`; the container
runs with the invoking user's UID/GID and the file is removed before the wrapper exits. The
JUnit report contains only test names/results. Fixture details and safety guards are documented in
`e2e/fixtures/wash-student/README.md`.

## Real `develop`

The smoke collection may be run against `develop` only from an approved network location and after its deployment is `Synced/Healthy`. Do not run the catalogue against `develop`: it includes mutating operations. Mutating workflows require authorized synthetic fixtures, unique runtime values and explicit cleanup. A workflow README is the source of truth for whether that workflow has actually passed live; prepared coverage is not a certification claim.

No tokens, passwords, personal data, fixture UUIDs or response captures belong in Git. Keep generated reports under `target/e2e/`, which is ignored by Maven conventions.

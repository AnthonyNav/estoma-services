# Platform BFF E2E harness

The harness has two deliberately different collections:

- `postman/platform-bff-e2e.postman_collection.json` is the safe smoke suite: health, runtime OpenAPI and unauthenticated boundary checks. It can be used against a local BFF or an approved `develop` endpoint.
- `postman/platform-bff-api.postman_collection.json` is a generated-style catalogue of the current 88 public OpenAPI operations. It is **not** a workflow suite and contains state-changing requests; run the static contract gate rather than running it blindly.
- `postman/workflows/` contains explicitly ordered, fixture-backed Front V1 workflows. Each workflow documents its own deployment and live-certification state.

## Static contract gate

Run without contacting any service:

```sh
node e2e/scripts/validate-openapi-postman.mjs
node e2e/scripts/validate-auth-workflow.mjs
node e2e/scripts/generate-wash-supervisor-workflow.mjs --check
node e2e/scripts/validate-wash-supervisor-workflow.mjs
node e2e/scripts/generate-wash-supervisor-entry-workflow.mjs --check
node e2e/scripts/validate-wash-supervisor-entry-workflow.mjs
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

After Supervisor Home and unified lookup are deployed, run their two-identity workflow only through
its guarded wrapper:

```sh
BFF_BASE_URL="$RUNTIME_BFF_URL" \
E2E_STUDENT_LOGIN_IDENTIFIER="$RUNTIME_E2E_STUDENT_LOGIN" \
E2E_STUDENT_PASSWORD="$RUNTIME_E2E_STUDENT_PASSWORD" \
E2E_SUPERVISOR_LOGIN_IDENTIFIER="$RUNTIME_E2E_SUPERVISOR_LOGIN" \
E2E_SUPERVISOR_PASSWORD="$RUNTIME_E2E_SUPERVISOR_PASSWORD" \
E2E_PSQL_EXEC="$PRIVATE_PSQL_EXEC" \
./e2e/runner/run-wash-supervisor.sh
```

The student schedules and cancels through the same certified public path. A different synthetic
account receives only temporary BFF-local Supervisor authorization. The collection takes a Home
baseline, waits for the appointment delta, exercises enrollment and controlled QR lookup, proves
the terminal `NONE` mapping after cancellation and logs out both sessions. The exit trap audits
that reads created no operation or wash execution before cleanup.

To certify the next WASH-SUP-01 state transition, use the dedicated two-phase
runner. It schedules with the student identity, waits for the appointment to
reach the Wash Execution owner, and then registers arrival and rejects entry
with the separate supervisor identity:

```sh
BFF_BASE_URL="$RUNTIME_BFF_URL" \
E2E_STUDENT_LOGIN_IDENTIFIER="$RUNTIME_E2E_STUDENT_LOGIN" \
E2E_STUDENT_PASSWORD="$RUNTIME_E2E_STUDENT_PASSWORD" \
E2E_SUPERVISOR_LOGIN_IDENTIFIER="$RUNTIME_E2E_SUPERVISOR_LOGIN" \
E2E_SUPERVISOR_PASSWORD="$RUNTIME_E2E_SUPERVISOR_PASSWORD" \
E2E_PSQL_EXEC="$PRIVATE_PSQL_EXEC" \
./e2e/runner/run-wash-supervisor-entry.sh
```

The opt-in arrival slot profile leaves the existing student and supervisor
workflow defaults unchanged. The private barrier verifies the exact
Appointment projection before the first arrival command. The expected public
sequence is `ENTRY -> ENTRY_DECISION -> NONE`, with owner states
`PENDING_ENTRY v1 -> ENTRY_REJECTED v2`. No cabin, tank, closure or resource
assignment is created or modified.

## Real `develop`

The smoke collection may be run against `develop` only from an approved network location and after its deployment is `Synced/Healthy`. Do not run the catalogue against `develop`: it includes mutating operations. Mutating workflows require authorized synthetic fixtures, unique runtime values and explicit cleanup. A workflow README is the source of truth for whether that workflow has actually passed live; prepared coverage is not a certification claim.

No tokens, passwords, personal data, fixture UUIDs or response captures belong in Git. Keep generated reports under `target/e2e/`, which is ignored by Maven conventions.

## Exit and completion static contract

`postman/workflows/wash-exit-completion-front-v1.postman_collection.json` is a generated static
contract for `IN_PROGRESS -> EXIT_SUBMITTED -> COMPLETED`. It covers negative exit cases, exact
idempotent retries, bounded polling, stale completion as `202` followed by
`REJECTED/VERSION_CONFLICT`, and exact terminal reads.

Do not execute it manually or add it to `runner/run.sh`. Live execution is unsupported and
unauthorized until a separate change supplies a private terminal manifest, exact three-store audit
and reentrant cleanup verified by failure injection. The existing authorized-entry cleanup remains
limited to `PENDING_ENTRY|1` and `IN_PROGRESS|2`.

```sh
node e2e/scripts/generate-wash-exit-completion-workflow.mjs --check
node e2e/scripts/validate-wash-exit-completion-workflow.mjs
```

A future runner must retain the private `0700` report and `0600` runtime-environment pattern, use
only Newman's CLI/JUnit reporters, preserve a request delay, revoke both sessions, and never persist
resolved environments, JSON reports, authenticated payloads or tokens.

The reassignment-candidates compatibility runner is a standalone isolated workflow. It creates its
own two synthetic identities, drives the scheduler's real Query/Result path, proves both a positive
candidate and a newly refreshed empty result after its tank becomes inactive, then performs fenced
manifest-only cleanup:

```sh
BFF_BASE_URL=http://127.0.0.1:18080 \
E2E_PSQL_EXEC="$PWD/e2e/runner/wash-isolated-psql.sh" \
E2E_PASSWORD_HASH_EXEC=/path/to/stdin-argon2-helper \
node e2e/runner/run-reassignment-candidates.mjs
```

The BFF reassignment-candidates rollout gate must already be enabled for this explicitly requested
test run. A prepared harness is not evidence of a live pass.

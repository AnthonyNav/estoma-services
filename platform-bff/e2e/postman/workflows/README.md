# Front V1 workflows

## Authentication

`platform-bff-auth-front-v1.postman_collection.json` is an ordered, mutating black-box suite for the public Platform BFF HTTP boundary. It covers:

- AUTH-01 login as the entry point for a normal account and an account that must change its password;
- AUTH-04 voluntary password change while preserving the authenticated session;
- AUTH-02 mandatory password change while preserving and promoting the restricted session;
- logout propagation back to the BFF session boundary.

AUTH-03 is intentionally absent because password recovery requires email delivery. The OpenAPI catalogue in `../platform-bff-api.postman_collection.json` remains an inventory and must not be used as a workflow suite.

## Fixture prerequisites

Use two authorized, isolated synthetic accounts. Do not use a real person's account or credentials.

1. `normalLoginIdentifier` has a valid permanent `normalPassword`, is active and starts in `NORMAL` state.
2. `temporaryLoginIdentifier` has a valid, unexpired `temporaryPassword`, is active and starts with mandatory password change enabled.
3. Both accounts have enough projected identity context for `GET /api/v1/me` after reaching `NORMAL`.
4. `normalNewPassword` and `requiredNewPassword` satisfy the Authentication password policy and differ from their corresponding initial passwords.
5. Reset or reprovision both fixtures before every full run. AUTH-04 replaces the normal password, and AUTH-02 consumes the temporary password.

Fixture provisioning and cleanup are deliberately outside this collection. No database bootstrap, secret, account identifier, host or captured response is stored in Git.

## Run from Postman

Import the collection and `platform-bff-auth-front-v1.environment.example.json`. Duplicate the environment locally and fill its **current values** only. Select the private environment and run the complete collection in its defined order.

`loginType` defaults to `USERNAME`. Set the corresponding value to `MATRICULA` when the synthetic identifier is numeric, following the Front classifier `^[0-9]+$ -> MATRICULA; otherwise USERNAME`.

The collection stores access tokens and session identifiers only as transient runtime collection variables. The final assertion in each folder removes them. If a run stops early, clear the runner state before exporting or sharing anything.

Session revocation is projected asynchronously back into the BFF. The final `/me` check therefore polls only while it still receives the valid transient `200`, waiting `revocationPollDelayMs` (default `500`) between attempts. It requires the terminal `401` within `revocationPollMaxAttempts` (default `30`, approximately 15 seconds); any other status fails immediately. These bounds can be lowered for local tests but should not be removed.

## Run with Newman

Copy the blank environment to an ignored or temporary location, populate that private copy, and pass it to Newman. Keeping secrets in a private environment file avoids putting them in shell arguments.

```sh
node e2e/scripts/validate-auth-workflow.mjs
npx --yes newman@6.2.1 run \
  e2e/postman/workflows/platform-bff-auth-front-v1.postman_collection.json \
  --environment /secure/or/temporary/platform-bff-auth-front-v1.environment.json \
  --bail \
  --reporters cli,junit,json \
  --reporter-junit-export target/e2e/auth-front-v1-junit.xml \
  --reporter-json-export target/e2e/auth-front-v1-newman.json
```

Newman reports contain resolved request bodies and Authorization headers. Treat them as sensitive runtime artifacts, keep them under ignored `target/e2e/`, and never commit or attach them without sanitization.

## Expected sequence

AUTH-04:

1. permanent login returns `200`, `NORMAL`, access token, refresh token and a session;
2. `/me` returns `200`;
3. voluntary change returns `200`, remains `NORMAL`, preserves `sessionId` and rotates both tokens;
4. login with the previous password returns `401`;
5. `/me` accepts the rotated token;
6. logout returns `revoked=true`, after which bounded polling of `/me` with that same token reaches `401`.

AUTH-02:

1. temporary login returns `200`, `PASSWORD_CHANGE_REQUIRED`, an access token and no refresh token;
2. `/me` returns `403` while the session is restricted;
3. change with `currentPassword: null` returns `200`, `NORMAL`, preserves `sessionId`, rotates the access token and issues a refresh token;
4. `/me` accepts the promoted token;
5. logout returns `revoked=true`, after which bounded polling of `/me` with that same token reaches `401`.

## Sanitized live evidence

Before this collection was authored, both journeys were exercised against the deployed `develop` BFF on 2026-09-04 with isolated synthetic fixtures. Every status and state transition above matched, session revocation reached the BFF, and the projection quarantine delta remained zero. This is evidence for the backend behavior mirrored by the collection; it is not a claim that the committed collection contains or can recreate those fixtures.

Do not add live credentials, fixture identifiers, email addresses, tokens, or captured responses.
The catalogue at `../platform-bff-api.postman_collection.json` is a contract inventory, not a
runnable workflow suite.

## Wash student

`wash-student-front-v1.postman_collection.json` covers the happy-path contract shared by
WASH-STU-01, WASH-STU-02 and WASH-STU-05:

```text
login
→ home without appointment
→ form context
→ availability (cache miss/retry is allowed)
→ schedule + poll
→ home scheduled with cancellation context
→ cancel + poll
→ home cancelled
→ logout
```

This is a mutating suite. It may run only with the isolated fixture created by
`../../fixtures/wash-student/fixture-db.sh`; the supported wrapper is
`../../runner/run-wash-student.sh`. The example environment is intentionally blank.

The Front-facing BFF surface exercised by the workflow is:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/auth/login` | Establish a `NORMAL` session. |
| `GET` | `/api/v1/wash/student/home` | Read no-appointment, scheduled and cancelled states. |
| `GET` | `/api/v1/wash/appointments/form-context` | Load student presentation and owned course-section choices. |
| `GET` | `/api/v1/wash/appointments/availability` | Read the fresh Appointments-owned decision; `503 + Retry-After` is the cache-aside refresh state. |
| `POST` | `/api/v1/wash/appointments` | Persist one idempotent schedule intent and return `202`. |
| `GET` | `/api/v1/operations/{operationId}` | Poll schedule/cancel until a terminal owner Result. |
| `POST` | `/api/v1/wash/appointments/{appointmentId}/cancel` | Persist one idempotent student-cancellation intent using the projected owner version. |
| `POST` | `/api/v1/auth/logout` | Revoke the workflow session. |

The collection derives account and service date server-side, selects only the exact synthetic NRC
and slot IDs supplied by the runtime fixture, reuses one stable idempotency key per intent, and
never sends student identity, arbitrary service date, cabin or tank data.

## Sanitized live evidence

The complete workflow passed against `develop` on 2026-09-04 after the Appointments owner and BFF
adapter were deployed. The first availability read returned the expected controlled
`503 + Retry-After`; its bounded retry returned the owner-derived `200` decision and isolated slot.
Schedule and cancellation were both accepted as `202`, reached terminal `SUCCEEDED` Results and
converged in student Home before logout. All JUnit assertions passed, and the independent cleanup
audit found zero synthetic periods, profiles, appointments, availability refreshes or run-scoped
operations in both BFF and Appointments. No credential, token, fixture UUID or captured payload is
part of this evidence.

## Wash supervisor Home and lookup

`wash-supervisor-front-v1.postman_collection.json` composes the already-certified student
appointment path with a distinct synthetic Supervisor session:

```text
supervisor login + Home baseline
→ student login + availability + schedule + projection convergence
→ supervisor Home delta
→ enrollment lookup returns ENTRY
→ QR lookup returns controlled 503
→ repeated enrollment lookup remains identical
→ student cancellation + projection convergence
→ terminal supervisor lookup returns NONE
→ Home cancellation delta
→ both sessions logout
```

Use only `../../runner/run-wash-supervisor.sh` with the paired fixtures. Home and lookup are reads;
the wrapper records the supervisor's pre-run client-operation count and verifies after Newman that
it did not change and that the appointment has no wash execution. This workflow therefore covers
WASH-SUP-06 and the side-effect-free lookup boundary of WASH-SUP-01; it does not claim that arrival
or entry-decision commands have been tested.

The collection and environment are generated deterministically from the student workflow plus the
Supervisor-specific requests. Update them with
`node ../../scripts/generate-wash-supervisor-workflow.mjs` and enforce a clean generation with
`--check`. URL, credentials, tokens and fixture identifiers remain blank in the example.

## Sanitized live evidence

The complete workflow passed against the deployed `develop` BFF on 2026-09-05 with two isolated
synthetic identities. Newman executed 21 requests, including one expected availability retry, and
all 52 assertions passed. Supervisor Home reflected the scheduled and cancelled appointment
deltas; enrollment lookup first returned `ENTRY` and, after cancellation, returned `NONE`; QR
lookup returned the expected controlled `503` while the appointment had no wash execution. The
side-effect audit found no supervisor client-operation delta and no wash execution, and the
independent post-run audit found zero transient BFF or Appointments rows. No credentials, tokens,
fixture UUIDs or captured payloads are part of this evidence.

## Wash supervisor arrival and rejected entry

`wash-supervisor-entry-front-v1.postman_collection.json` extends the certified appointment
precondition into the deterministic rejection branch of WASH-SUP-01:

```text
student schedules appointment
→ private owner-projection barrier
→ supervisor lookup returns ENTRY
→ RegisterWashArrival succeeds
→ lookup returns PENDING_ENTRY / ENTRY_DECISION
→ DecideWashEntry(REJECTED) succeeds
→ lookup returns ENTRY_REJECTED / NONE
```

The collection has separate `Prepare appointment` and `Arrival and rejected entry` folders
because Wash Execution must consume the real `AppointmentScheduled` event before arrival.
Runtime state crosses that barrier only through a private ephemeral Postman environment. The
supported wrapper is `../../runner/run-wash-supervisor-entry.sh`; running one folder manually
does not establish the fixture or cleanup guarantees.

This branch matches the closed Front V1 rejection mapping: identity is confirmed, requirements are
not satisfied, the free-text reason is required and the functional rejection still completes the
durable command as `SUCCEEDED`. It deliberately excludes entry authorization, resource
allocation, pending reassignment, exit/completion, QR and email. Those are separate workflows.

The generated collection and blank example environment are updated with
`node ../../scripts/generate-wash-supervisor-entry-workflow.mjs`; CI enforces `--check` plus
the dedicated validator.

### Sanitized live evidence — 2026-09-05

The full wrapper passed against the already-deployed services:
`platform-bff:develop-6ebf0f4`, `appointments-service:develop-2d7d63c` and
`wash-execution-service:develop-6e5bc68`. Newman executed 18 requests (17 definitions plus one
expected availability cache-miss retry) and all 42 assertions passed. Both commands returned
`202`, their public operations reached `SUCCEEDED`, and lookup exposed
`ENTRY -> ENTRY_DECISION -> NONE`. The appointment remains `SCHEDULED` while its execution is
`PENDING_ENTRY v1`; rejection converges to `ENTRY_REJECTED v2` in both owners and BFF projections.

The owner audit verified the rejected validation, both published Events and zero resource
assignments. All three fixture cleanups completed, and an independent, read-only audit found zero
run-scoped execution/appointment/projection/message rows in Wash Execution, Appointments and BFF;
the previously absent owner role was absent again. This includes Appointments' consumed arrival
message, whose evidence must be captured before deleting the appointment. All three manifests
were removed only after cleanup. The three Deployments remained available `1/1`; their sampled
15-minute logs contained no `ERROR`/`Exception` lines. Relevant Appointments/Wash Execution queues,
retry queues and DLQs, plus `results.bff`, had zero ready/unacknowledged messages at inspection.

The pinned Maven image passed `clean verify`: 53 integration tests, zero failures/errors.
Static contract/generation gates, cleanup-retry regressions, interrupted-arrival cleanup and runner
failure/dependency-retention tests also passed. These automated recovery tests use inert mocks;
the live run certified the successful rejected-entry path, not an injected live failure.
No credentials, tokens, runtime UUIDs or response captures are included here.

## Wash supervisor authorized entry — isolated environment only

`wash-supervisor-authorized-front-v1.postman_collection.json` covers the positive
branch of WASH-SUP-01, using the same public scheduling/arrival endpoints followed
by `POST /api/v1/wash/executions/{washExecutionId}/entry-decision` with
`identityConfirmed=true`, `requirementsSatisfied=true`, `decision=AUTHORIZED`
and the current `expectedVersion`. No resource IDs are sent in that decision:
the owner selects them. The suite verifies the exact synthetic cabin/tank via
supervisor lookup and expects `nextAction=EXIT_REVIEW`.

This suite MUST NOT run against shared develop. Its fixed executor
`../../runner/wash-isolated-psql.sh` targets only desktop/wash-e2e/PostgreSQL.
An independently implemented executor must prove the same isolation through
`--assert-isolated-wash`. An environment-variable assertion alone is insufficient.

1. Ensure the opt-in `wash-e2e` environment is healthy and exclusively allocated
   to this run. It supplies empty READY prerequisite snapshots, not live owner recovery.
2. Bootstrap `../../fixtures/isolated-identities/fixture-db.sh` with the executor,
   a private `E2E_REPORT_DIR`, and `E2E_PASSWORD_HASH_EXEC` (production Argon2 hash
   on stdout, password received over stdin). See its README. Never print credentials.
3. Open a private localhost tunnel to **this namespace's BFF**. Set `BFF_BASE_URL`,
   `E2E_REPORT_DIR`, `E2E_PSQL_EXEC`; run
   `node ../../runner/run-wash-supervisor-authorized.mjs`. It reads the protected
   identity JSON as data and invokes the two-phase wrapper. Do not run folders alone.
4. The wrapper audits and cleans owner/student/supervisor fixtures. If any cleanup
   fails, preserve the manifests and repair/retry in that dependency order.
5. After those fixtures are gone, run identity `cleanup`; then
   `E2E_PSQL_EXEC=<isolated-executor> node ../../scripts/audit-wash-isolated-cleanup.mjs`.
   This independent read-only audit expects an exclusively used, empty fixture
   inventory. It does not erase data or assert that RabbitMQ is empty.
6. Inspect retry/DLQ counts separately, retain sanitized evidence, close the tunnel.

### Live evidence — 2026-09-05

Pinned isolated runtime: BFF `610488b`, Authentication `bfda392`, Appointments
`2d7d63c`, Wash Execution `6e5bc68`. Two fresh accounts logged in NORMAL and
logged out successfully. The full public workflow executed **18 requests / 42
assertions, zero failures** (17 definitions plus the expected availability retry).
Arrival and entry returned 202 and reached SUCCEEDED. Lookup converged to
IN_PROGRESS execution v2, INITIAL automatic assignment to the exact fixture
resources and EXIT_REVIEW. Appointments/BFF appointment projections reached v3.

The owner audit verified all four published execution Events and the assignment.
Cleanup checked consumed event evidence and removed its scoped data; identity
cleanup also passed. An independent audit found **28 fixture tables empty**
across Authentication, BFF, Appointments and Wash Execution. Sampled logs had no
ERROR/Exception and both isolated/shared checked Deployments remained available.

**Operational gate remains open:** one `WashResourceAssigned` Event reached
`events.appointments.dlq`. Appointments currently binds `wash-execution.#` but its
validator does not accept this event. The message was inspected with requeue and
retained, not purged. This is a separate Appointments topology defect; the
functional pass must NOT be described as clean messaging certification. Correct
the subscription and migrate the persisted wildcard, then rerun and require zero
new retry/DLQ messages. Reassignment, exit/completion and email flows are not
certified by this increment.

## Wash exit and completion — isolated runner

`wash-exit-completion-front-v1.postman_collection.json` extends the generated authorized-entry
contract with student exit and supervisor completion. The public example environment contains empty
placeholders only. The exact supervisor read is terminal authority; Student Home is supporting
evidence only when it still selects the same appointment.

Do not run the collection manually. The supported wrapper is
`../../runner/run-wash-exit-completion.mjs`, which requires the isolated SQL executor and protected
synthetic identity credentials. It composes the existing fixtures, audits the exact terminal history
and uses an opt-in terminal cleanup manifest. The original authorized-entry cleanup behavior remains
unchanged when that manifest is absent.

If Newman or the terminal audit fails, the wrapper preserves manifests, database state and the last
runtime Postman state for reconciliation. It never cleans an ambiguous terminal run or generates a
replacement idempotency key. Static checks execute representative assertions against valid and
altered identity, materials, version, assignment, deduplication and conflict responses:

```sh
node ../../scripts/generate-wash-exit-completion-workflow.mjs --check
node ../../scripts/validate-wash-exit-completion-workflow.mjs
```

Passing these checks is not live certification and does not prove provisioned identities.

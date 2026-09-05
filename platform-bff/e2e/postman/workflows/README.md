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

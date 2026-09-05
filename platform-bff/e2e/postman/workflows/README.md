# Front V1 authentication workflows

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
6. logout returns `revoked=true`, after which `/me` with that same token returns `401`.

AUTH-02:

1. temporary login returns `200`, `PASSWORD_CHANGE_REQUIRED`, an access token and no refresh token;
2. `/me` returns `403` while the session is restricted;
3. change with `currentPassword: null` returns `200`, `NORMAL`, preserves `sessionId`, rotates the access token and issues a refresh token;
4. `/me` accepts the promoted token;
5. logout returns `revoked=true`, after which `/me` with that same token returns `401`.

## Sanitized live evidence

Before this collection was authored, both journeys were exercised against the deployed `develop` BFF on 2026-09-04 with isolated synthetic fixtures. Every status and state transition above matched, session revocation reached the BFF, and the projection quarantine delta remained zero. This is evidence for the backend behavior mirrored by the collection; it is not a claim that the committed collection contains or can recreate those fixtures.

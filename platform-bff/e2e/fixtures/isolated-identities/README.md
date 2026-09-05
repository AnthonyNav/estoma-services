# Isolated identity fixture

This fixture creates the two fresh Authentication identities and matching BFF presentation rows
needed by the isolated Wash E2E. It is intentionally limited to a database executor that can prove
the fixed `wash-e2e` target before every mutation.

## Interface

```sh
E2E_PSQL_EXEC=/path/to/wash-isolated-psql.sh \
E2E_PASSWORD_HASH_EXEC=/path/to/stdin-argon2-helper \
./fixture-db.sh bootstrap

E2E_PSQL_EXEC=/path/to/wash-isolated-psql.sh \
./fixture-db.sh cleanup
```

`E2E_PASSWORD_HASH_EXEC` must read one password from standard input and print exactly one encoded
Argon2id hash generated with Authentication's `Argon2PasswordHasher`. Plaintext passwords are never
passed in arguments or environment variables.

Bootstrap creates `identity-fixture.env` (non-secret retry identifiers) and
`identity-credentials.json` (login identifiers and passwords). Both are mode `0600`; the JSON file
must be parsed as data and passed directly to the E2E subprocess environment, never sourced. Neither
file belongs in reports or Git. The manifest is written before database mutation so a partial
Authentication/BFF bootstrap can be retried safely.

Cleanup must run only after the wash entry, supervisor and student fixtures have cleaned their
dependent rows. It refuses active sessions, pending Authentication outbox events, or remaining BFF
authorization/academic/appointment data. Cleanup removes only the two manifest identities, their
revoked session evidence and Authentication-owned session rows. It deletes both local files only
after the database audit succeeds.

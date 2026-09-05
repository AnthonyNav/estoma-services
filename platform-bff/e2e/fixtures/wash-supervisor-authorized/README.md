# Isolated authorized-entry fixture

This fixture extends the rejected-entry bootstrap with one private `NORMAL` cabin and one capacity-one
tank, then exercises the positive `AUTHORIZED` branch. It is permitted only when
`E2E_ISOLATED_NAMESPACE=wash-e2e` and the private `E2E_PSQL_EXEC --assert-isolated-wash` preflight
independently verifies the fixed isolated deployment and database markers.

Run it after the existing student and supervisor fixtures are active. It uses their manifest paths,
the existing stdin SQL executor contract, and `E2E_ENTRY_FIXTURE_MANIFEST` (default
`<report>/entry-fixture.env`). Bootstrap invokes `../wash-supervisor-entry/fixture-db.sh bootstrap`,
then appends `CABIN_ID`, `TANK_ID`, codes and names before inserting the private Wash Execution and
BFF resource projections. The
runner uses the inherited `<RUN_KEY>-register-arrival` and `<RUN_KEY>-decide-entry` keys.
Bootstrap refuses any pre-existing active `NORMAL`/`JOURNEY` candidate, making automatic allocation
to the manifest tank deterministic rather than relying on resource ordering.

Audit requires the exact positive result: Wash Execution `IN_PROGRESS` v2, an `AUTHORIZED` validation,
one active automatic `INITIAL` assignment to the manifest tank, and published
`WashArrivalRegistered` v1 plus `WashEntryAuthorized`, `WashResourceAssigned`, and
`WashExecutionStarted` v2. Appointments must be `IN_PROGRESS` v3, while the BFF must contain the
matching appointment, execution, and assignment projections.

Cleanup must precede upstream student and supervisor cleanup. It waits for all three services to
quiesce and accepts only an independently proven untouched `PENDING_ENTRY` v1 interruption or the
exact positive `IN_PROGRESS` v2 path. It never changes business state. It deletes dependent owner
rows before the execution, deletes the BFF assignment before the BFF execution, restores the exact
prior Wash Execution role, and removes only the manifest-owned cabin/tank. Appointments evidence and
the appointment itself remain owned by the student fixture.

Both database phases are retryable. A failed cleanup retains the manifest; retry with the same
executor, database names, isolation marker, and upstream manifests. Never remove the manifest
manually, coerce execution state, purge shared queues, or use this fixture outside `wash-e2e`.

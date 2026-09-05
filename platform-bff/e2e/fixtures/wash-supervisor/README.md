# Isolated WASH-SUP fixture

This fixture grants one separate `e2e-*` account the minimum local BFF authorization required to
read Supervisor Home and perform the unified lookup. It does not modify Authentication, Users,
Appointments, Wash Execution or Wash Administration, and it never creates an arrival or wash
execution.

The student appointment prerequisite remains owned by the existing
`../wash-student/fixture-db.sh`: the student schedules and cancels through the public BFF,
RabbitMQ and Appointments. The supervisor fixture is installed only after the student fixture and
requires a different active synthetic account whose projected role is absent or exactly
`E2E_TESTER` and whose Lavado access is absent.

The private manifest records the original role source version and timestamp, the generated access
identifier and the supervisor's pre-run client-operation count. `audit` proves that Home and the
repeated enrollment/QR lookups created neither a `client_operation` nor a wash execution for the
isolated appointment. Cleanup removes only the generated access and restores the exact original
role row, or removes the temporary role when it was absent before bootstrap.

Use only through `../../runner/run-wash-supervisor.sh`. The runner keeps both identities, both
credentials, access tokens, concrete UUIDs and database connectivity outside Git and exported
reports. If cleanup fails, preserve both fixture manifests and retry with the same private database
executor; do not delete rows manually or purge shared queues.

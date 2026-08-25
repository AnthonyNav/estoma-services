# Public HTTP API — PR5

All business writes require an authenticated JWT with normal authentication state and an
`Idempotency-Key` request header. They enqueue exactly one allowlisted owner Command and return
`202 Accepted` with `operationId`, `status`, `operationUrl`, `acceptedAt`, and `reused`.
Immediate validation, authorization, and projection-readiness errors use
`application/problem+json`.

## Mailbox

```text
GET  /api/v1/mailbox/messages
GET  /api/v1/mailbox/summary
POST /api/v1/mailbox/visits
```

Mailbox reads are scoped to the authenticated account. `POST /visits` receives `systemCode` and
`visitedAt`; the account id is always taken from the JWT and maps to `RegisterInboxVisit`.

## Wash

```text
POST /api/v1/wash/appointments
POST /api/v1/wash/appointments/{appointmentId}/cancel
POST /api/v1/wash/exceptional-authorizations
POST /api/v1/wash/exceptional-authorizations/{authorizationId}/cancel

POST /api/v1/wash/executions/arrivals
POST /api/v1/wash/executions/{washExecutionId}/entry-decision
POST /api/v1/wash/executions/{washExecutionId}/reassignment
POST /api/v1/wash/executions/{washExecutionId}/exit
POST /api/v1/wash/executions/{washExecutionId}/complete
POST /api/v1/wash/executions/{washExecutionId}/clinic-cancel
POST /api/v1/wash/operational-resources/disable
POST /api/v1/wash/operational-resources/restore

GET  /api/v1/wash/supervision/pending-reassignments
```

Student operations use the authenticated account and local appointment/execution ownership;
supervisor operations require the local `SUPERVISOR_LAVADO` role and active
`LAVADO_ULTRASONICO` access projection. The pending-reassignments view is composed locally from
the person, academic, appointment, execution, assignment, cabin, and tank projections. Its
`qrUsageContext` only repeats the canonical `appointmentId`; it is not a persisted QR entity.

`GET /api/v1/wash/appointments/availability` remains intentionally absent until the Appointments
owner publishes a consumable availability contract. No `/api/v1/admin/**` route is exposed here.

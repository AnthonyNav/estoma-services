# Appointment QR projection rollout

This consumer-compatibility stage accepts `AppointmentScheduled` V1 with or without the
optional `qrRepresentation` field. When present, the value must contain 1..2000 characters.
The BFF treats it as an opaque owner-issued reference: it neither parses its format nor derives
it from `appointmentId`.

The value is isolated in `appointment_qr_projection`, together with SHA-256 over its exact
UTF-8 bytes. The digest has a partial unique index for a future local lookup. Both scheduling
and invalidation are fenced by `appointmentVersion`. Cancellation, missed, entry rejection and
completion clear the value; older events cannot restore it. `AppointmentStarted` preserves it.

This stage does not expose the representation through Student Home and does not enable QR
lookup. Existing API responses continue returning the current gated `null` value. It adds no
token parser, UUID extraction, signature verification or QR business entity. Raw representations
are not added to application logs, error messages or traces.

The internal recovery consumer requests owner snapshots by `serviceDate`. A durable generation
advances one page at a time and must complete two passes before that date is internally `READY`.
Results are correlated to the current query and generation; duplicate and out-of-order pages do
not advance it. Invalid current pages, token cycles and same-version/different-value conflicts
leave the date `DEGRADED`. A timed-out or exhausted query is replaced with a new message ID, so a
process restart can continue from the persisted page. An explicit start creates a new generation
without rewriting the previous job's terminal history. Readiness is date-scoped and internal.

For an operational restart of the current date after `DEGRADED`, increment
`BFF_APPOINTMENT_QR_RECOVERY_RESTART_REVISION` and redeploy. The scheduler consumes a greater
revision once, starts a new generation, and records it in the date state. Reusing the same value
does not restart a rejected generation. A date first seen at that revision stores it immediately,
so completion does not trigger a second full recovery. Do not change the revision while a job is
`RUNNING`; it is evaluated only after the generation reaches a terminal state.

Recovery is disabled by default with `BFF_APPOINTMENT_QR_RECOVERY_ENABLED=false`. Enable it only
after the owner query is enabled and **every** BFF replica runs this recovery stage. The recovery
writer and incremental event writer share per-appointment locks; an older BFF replica does not,
so a mixed rollout is not a safe bootstrap window. Duplicate opaque representations remain a
technical uniqueness failure and are retried instead of selecting an arbitrary appointment.
The flag gates scheduler starts; it is not a kill switch. Once a generation is `RUNNING`, the
durable relay and Result handler continue it across a flag change so accepted owner Results are
not quarantined halfway through recovery.

Two successful passes alone do not prove completeness. Appointment event consumption must stay
continuous throughout bootstrap, then operations must verify the date has no missing active QR
projection before enabling the public read flag. A new service date has its own recovery state
and cannot inherit another date's `READY` status.

## Public read activation

Student Home and supervisor QR lookup are implemented behind
`BFF_WASH_QR_ENABLED=false`. Enabling the flag does not bypass recovery: an active appointment
requires the current `America/Mexico_City` service date to be `READY`. Student Home returns the
exact opaque owner representation for `SCHEDULED` and `IN_PROGRESS` appointments, including
`PENDING_REASSIGNMENT`; terminal appointments return `null` without depending on QR readiness.
An active appointment with a missing, future-version or contradictory QR mapping returns 503.

Supervisor QR lookup hashes the supplied representation for indexed local lookup and then
compares the exact value. Unknown and tombstoned representations return the same 404 response.
A mapping whose appointment is absent for the current service date, terminal, incoherent or
behind an impossible future mapping returns 503. Lookup performs no owner RPC and publishes no
command. Keep the public flag off until the owner query and recovery scheduler are enabled, all
BFF replicas run the recovery implementation, the date is `READY`, event consumption stayed
continuous, and the active-appointment audit reports no missing mappings.

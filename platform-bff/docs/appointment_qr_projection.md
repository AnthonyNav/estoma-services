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
snapshot/recovery handler, token parser, UUID extraction, signature verification or QR business
entity. Raw representations are not added to application logs, error messages or traces.

The owner producer can add the field only after this compatible consumer is deployed. Future
Home and lookup activation remains gated on an owner-defined recovery mechanism for active
historical appointments and evidence that the projection is complete.

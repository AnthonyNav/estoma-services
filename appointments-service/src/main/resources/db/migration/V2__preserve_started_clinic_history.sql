-- An execution may start and later end in CANCELLED + CLINIC after resource loss.
-- started_at remains historical evidence and must not be cleared by the terminal state.
ALTER TABLE appointments DROP CONSTRAINT ck_appointments_started;
ALTER TABLE appointments ADD CONSTRAINT ck_appointments_started
  CHECK (status NOT IN ('IN_PROGRESS', 'COMPLETED') OR started_at IS NOT NULL);

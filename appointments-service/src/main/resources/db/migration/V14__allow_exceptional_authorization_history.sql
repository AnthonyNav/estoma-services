-- A supervisor may record a new decision after the previous authorization was cancelled or consumed.
-- A durable supersession prevents a consumed historical authorization from becoming actionable again
-- if its appointment is later cancelled by the clinic.
ALTER TABLE exceptional_appointment_authorizations
  DROP CONSTRAINT uq_exceptional_authorizations_student_date;

ALTER TABLE exceptional_appointment_authorizations
  ADD COLUMN superseded_by_authorization_id uuid
    REFERENCES exceptional_appointment_authorizations (exceptional_authorization_id),
  ADD COLUMN superseded_at timestamptz,
  ADD CONSTRAINT ck_exceptional_authorizations_supersession CHECK (
    (superseded_by_authorization_id IS NULL AND superseded_at IS NULL)
    OR (superseded_by_authorization_id IS NOT NULL AND superseded_at IS NOT NULL)
  );

CREATE INDEX ix_exceptional_authorizations_student_date_granted
  ON exceptional_appointment_authorizations (student_account_id, service_date, granted_at DESC);

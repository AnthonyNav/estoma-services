-- A supervisor may record a new decision after the previous authorization was cancelled or consumed.
-- Concurrent grants are serialized on the required student academic-profile row in the application
-- transaction, then re-evaluate the history before inserting the new authorization.
ALTER TABLE exceptional_appointment_authorizations
  DROP CONSTRAINT uq_exceptional_authorizations_student_date;

CREATE INDEX ix_exceptional_authorizations_student_date_granted
  ON exceptional_appointment_authorizations (student_account_id, service_date, granted_at DESC);

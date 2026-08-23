ALTER TABLE appointments
    DROP CONSTRAINT ck_appointments_cancellation_metadata;

ALTER TABLE appointments
    ADD CONSTRAINT ck_appointments_cancellation_metadata CHECK (
      (cancellation_type = 'CLINIC'
        AND cancellation_subreason IS NOT NULL
        AND btrim(cancellation_subreason) <> '')
      OR (cancellation_type = 'STUDENT' AND cancellation_subreason IS NULL)
      OR (cancellation_type IS NULL AND cancellation_subreason IS NULL)
    );

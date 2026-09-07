ALTER TABLE entry_validations
  DROP CONSTRAINT ck_entry_validations_values,
  ALTER COLUMN identity_confirmed DROP NOT NULL,
  ALTER COLUMN requirements_satisfied DROP NOT NULL,
  ADD CONSTRAINT ck_entry_validations_values CHECK (
    (
      decision = 'AUTHORIZED'
      AND identity_confirmed IS TRUE
      AND requirements_satisfied IS TRUE
      AND rejection_reason IS NULL
    )
    OR
    (
      decision = 'REJECTED'
      AND btrim(coalesce(rejection_reason, '')) <> ''
      AND (
        (identity_confirmed IS NULL AND requirements_satisfied IS NULL)
        OR
        (identity_confirmed IS NOT NULL AND requirements_satisfied IS NOT NULL)
      )
    )
  );

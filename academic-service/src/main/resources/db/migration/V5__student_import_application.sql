ALTER TABLE student_import_plan_items
  ADD COLUMN next_attempt_at timestamptz,
  ADD COLUMN first_published_at timestamptz,
  ADD COLUMN last_progress_at timestamptz;

CREATE INDEX ix_student_import_plan_due
ON student_import_plan_items (status, next_attempt_at)
WHERE status IN ('PENDING', 'PROCESSING');

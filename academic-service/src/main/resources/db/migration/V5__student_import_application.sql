ALTER TABLE student_import_plan_items
  ADD COLUMN next_attempt_at timestamptz,
  ADD COLUMN first_published_at timestamptz,
  ADD COLUMN current_operation_id uuid,
  ADD COLUMN last_result_message_id uuid,
  ADD COLUMN last_result_status varchar(20),
  ADD CONSTRAINT ck_student_import_plan_last_result_status
    CHECK (last_result_status IS NULL OR last_result_status IN ('SUCCEEDED','REJECTED','FAILED','EXPIRED'));

ALTER TABLE student_import_processes
  ADD COLUMN last_progress_at timestamptz,
  ADD COLUMN last_progress_completed_items integer NOT NULL DEFAULT 0,
  ADD COLUMN last_progress_percent integer NOT NULL DEFAULT 0;

ALTER TABLE account_status_projection
  ADD COLUMN last_deactivation_reason_code varchar(80);

CREATE TABLE student_import_resume_audit (
  audit_id uuid PRIMARY KEY,
  import_id uuid NOT NULL REFERENCES student_import_processes ON DELETE CASCADE,
  operation_id uuid NOT NULL,
  correlation_id uuid NOT NULL,
  requested_by_account_id uuid NOT NULL,
  previous_status varchar(30) NOT NULL,
  execution_generation integer NOT NULL,
  result varchar(30) NOT NULL,
  resumed_at timestamptz NOT NULL
);

CREATE INDEX ix_student_import_plan_due
ON student_import_plan_items (status, next_attempt_at)
WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX ix_student_import_resume_audit
ON student_import_resume_audit (import_id, resumed_at DESC);

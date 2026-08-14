CREATE TYPE student_import_validation_work_status AS ENUM ('PENDING', 'WAITING_RESULT', 'SUCCEEDED', 'FAILED');

CREATE TABLE student_import_validation_rows (
  import_id uuid NOT NULL REFERENCES student_import_processes ON DELETE CASCADE,
  row_type varchar(20) NOT NULL CHECK (row_type IN ('NRC', 'STUDENT', 'ENROLLMENT')),
  sequence integer NOT NULL,
  sheet_name varchar(80) NOT NULL,
  row_number integer NOT NULL,
  row_data jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (import_id, row_type, sequence)
);

CREATE TABLE student_import_validation_work (
  import_id uuid NOT NULL REFERENCES student_import_processes ON DELETE CASCADE,
  work_id uuid NOT NULL,
  work_type varchar(80) NOT NULL,
  work_key varchar(320) NOT NULL,
  status student_import_validation_work_status NOT NULL,
  current_message_id uuid,
  current_operation_id uuid,
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz,
  result jsonb,
  last_error_code varchar(80),
  last_error_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (import_id, work_id),
  UNIQUE (import_id, work_type, work_key)
);

CREATE INDEX ix_student_import_validation_work_due
ON student_import_validation_work (status, next_attempt_at)
WHERE status IN ('PENDING', 'WAITING_RESULT');

CREATE TABLE student_import_plan_item_rebases (
  rebase_id uuid PRIMARY KEY,
  import_id uuid NOT NULL,
  plan_item_id uuid NOT NULL,
  execution_generation integer NOT NULL,
  previous_expected_versions jsonb NOT NULL,
  new_expected_versions jsonb NOT NULL,
  reason varchar(500) NOT NULL,
  resolved_by_account_id uuid NOT NULL,
  resolved_at timestamptz NOT NULL,
  FOREIGN KEY (import_id, plan_item_id)
    REFERENCES student_import_plan_items (import_id, plan_item_id)
);

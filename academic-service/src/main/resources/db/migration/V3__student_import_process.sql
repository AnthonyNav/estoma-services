CREATE TYPE student_import_status AS ENUM (
  'VALIDATING', 'VALIDATED', 'REJECTED', 'APPLYING', 'COMPLETED', 'APPLICATION_FAILED'
);

CREATE TYPE student_import_plan_item_status AS ENUM ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED');

CREATE TYPE student_import_plan_item_type AS ENUM (
  'REGISTER_COURSE_SECTION', 'ACTIVATE_COURSE_SECTION', 'DEACTIVATE_COURSE_SECTION',
  'CREATE_PERSON', 'UPDATE_PERSON', 'CREATE_ACCOUNT', 'ACTIVATE_ACCOUNT', 'DEACTIVATE_ACCOUNT',
  'ASSIGN_STUDENT_ROLE', 'GRANT_STUDENT_SYSTEM_ACCESS', 'CREATE_STUDENT_PROFILE',
  'CHANGE_STUDENT_SEMESTER', 'ACTIVATE_STUDENT_PROFILE', 'DEACTIVATE_STUDENT_PROFILE',
  'ADD_STUDENT_ENROLLMENT', 'REACTIVATE_STUDENT_ENROLLMENT', 'REMOVE_STUDENT_ENROLLMENT',
  'FINALIZE_STUDENT_IMPORT'
);

CREATE TABLE student_import_processes (
  import_id uuid PRIMARY KEY,
  academic_period_id uuid NOT NULL REFERENCES academic_periods,
  academic_period_code varchar(60) NOT NULL,
  academic_period_version integer NOT NULL,
  import_mode varchar(20) NOT NULL CHECK (import_mode = 'FULL'),
  file_reference varchar(1000) NOT NULL,
  file_checksum varchar(256) NOT NULL,
  status student_import_status NOT NULL,
  started_by_account_id uuid NOT NULL,
  authorized_by_account_id uuid NOT NULL,
  process_version integer NOT NULL DEFAULT 1,
  execution_generation integer NOT NULL DEFAULT 0,
  failed_step varchar(120),
  failure_code varchar(80),
  failure_reason varchar(500),
  recoverable boolean,
  started_at timestamptz NOT NULL,
  validated_at timestamptz,
  application_started_at timestamptz,
  completed_at timestamptz,
  failed_at timestamptz,
  last_resumed_by_account_id uuid,
  last_resumed_at timestamptz,
  students_created integer NOT NULL DEFAULT 0,
  students_updated integer NOT NULL DEFAULT 0,
  students_reactivated integer NOT NULL DEFAULT 0,
  students_deactivated integer NOT NULL DEFAULT 0,
  course_sections_created integer NOT NULL DEFAULT 0,
  course_sections_activated integer NOT NULL DEFAULT 0,
  course_sections_deactivated integer NOT NULL DEFAULT 0,
  enrollments_registered integer NOT NULL DEFAULT 0,
  enrollments_reactivated integer NOT NULL DEFAULT 0,
  enrollments_removed integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX uq_student_import_one_active
ON student_import_processes ((true))
WHERE status IN ('VALIDATING', 'VALIDATED', 'APPLYING', 'APPLICATION_FAILED');

CREATE INDEX ix_student_import_period ON student_import_processes (academic_period_id, started_at DESC);

CREATE TABLE student_import_plan_items (
  import_id uuid NOT NULL REFERENCES student_import_processes ON DELETE CASCADE,
  plan_item_id uuid NOT NULL,
  item_type student_import_plan_item_type NOT NULL,
  status student_import_plan_item_status NOT NULL DEFAULT 'PENDING',
  business_key jsonb NOT NULL,
  planned_effect jsonb NOT NULL,
  expected_versions jsonb NOT NULL DEFAULT '{}'::jsonb,
  generated_entity_id uuid,
  execution_generation integer NOT NULL DEFAULT 0,
  current_command_message_id uuid,
  current_idempotency_key varchar(200),
  received_result jsonb,
  applied_aggregate_version integer,
  attempts integer NOT NULL DEFAULT 0,
  last_error_code varchar(80),
  last_error_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (import_id, plan_item_id)
);

CREATE INDEX ix_student_import_plan_pending
ON student_import_plan_items (import_id, status, created_at);

CREATE TABLE student_import_validation_errors (
  import_id uuid NOT NULL REFERENCES student_import_processes ON DELETE CASCADE,
  sequence integer NOT NULL,
  error_code varchar(80) NOT NULL,
  sheet_name varchar(80),
  row_number integer,
  column_name varchar(120),
  message varchar(500) NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (import_id, sequence)
);

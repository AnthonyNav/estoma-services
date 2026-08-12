CREATE TYPE academic_profile_status AS ENUM ('ACTIVE', 'INACTIVE');
CREATE TYPE academic_period_status AS ENUM ('ACTIVE', 'CLOSED');
CREATE TYPE academic_data_status AS ENUM ('PENDING_IMPORT', 'IMPORTING', 'READY', 'IMPORT_FAILED');
CREATE TYPE course_section_status AS ENUM ('ACTIVE', 'INACTIVE');
CREATE TYPE enrollment_status AS ENUM ('ACTIVE', 'REMOVED');
CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED');

CREATE TABLE student_academic_profiles (
  student_academic_profile_id uuid PRIMARY KEY,
  person_id uuid NOT NULL UNIQUE,
  account_id uuid NOT NULL UNIQUE,
  student_number varchar(80) NOT NULL,
  student_number_normalized varchar(80) NOT NULL UNIQUE,
  current_semester integer NOT NULL CHECK (current_semester BETWEEN 1 AND 14),
  academic_status academic_profile_status NOT NULL,
  version integer NOT NULL DEFAULT 1,
  student_number_version integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);

CREATE TABLE student_academic_eligibilities (
  eligibility_id uuid PRIMARY KEY,
  student_academic_profile_id uuid NOT NULL REFERENCES student_academic_profiles,
  system_code varchar(80) NOT NULL,
  eligible boolean NOT NULL,
  policy_version integer NOT NULL,
  eligibility_version integer NOT NULL,
  academic_profile_version integer NOT NULL,
  evaluated_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  UNIQUE (student_academic_profile_id, system_code)
);

CREATE TABLE student_number_change_history (
  change_id uuid PRIMARY KEY,
  student_academic_profile_id uuid NOT NULL REFERENCES student_academic_profiles,
  previous_student_number varchar(80) NOT NULL,
  new_student_number varchar(80) NOT NULL,
  reason varchar(500) NOT NULL,
  corrected_by_account_id uuid NOT NULL,
  correlation_id uuid,
  corrected_at timestamptz NOT NULL
);

CREATE TABLE academic_periods (
  academic_period_id uuid PRIMARY KEY,
  code varchar(60) NOT NULL UNIQUE,
  name varchar(160) NOT NULL,
  start_date date NOT NULL,
  end_date date NOT NULL,
  status academic_period_status NOT NULL,
  academic_data_status academic_data_status NOT NULL DEFAULT 'PENDING_IMPORT',
  version integer NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CHECK (end_date >= start_date)
);

CREATE UNIQUE INDEX uq_academic_period_single_active
ON academic_periods (status)
WHERE status = 'ACTIVE';

CREATE TABLE course_sections (
  course_section_id uuid PRIMARY KEY,
  academic_period_id uuid NOT NULL REFERENCES academic_periods,
  nrc varchar(40) NOT NULL,
  name varchar(160) NOT NULL,
  status course_section_status NOT NULL DEFAULT 'ACTIVE',
  version integer NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  UNIQUE (academic_period_id, nrc)
);

CREATE TABLE student_course_sections (
  enrollment_id uuid NOT NULL UNIQUE,
  student_academic_profile_id uuid NOT NULL REFERENCES student_academic_profiles,
  course_section_id uuid NOT NULL REFERENCES course_sections,
  status enrollment_status NOT NULL DEFAULT 'ACTIVE',
  enrolled_at timestamptz NOT NULL,
  removed_at timestamptz,
  version integer NOT NULL DEFAULT 1,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (student_academic_profile_id, course_section_id)
);

CREATE TABLE person_status_projection (
  person_id uuid PRIMARY KEY,
  status varchar(20) NOT NULL,
  source_version integer NOT NULL,
  updated_at timestamptz NOT NULL
);

CREATE TABLE account_status_projection (
  account_id uuid PRIMARY KEY,
  person_id uuid,
  login_type varchar(40),
  status varchar(20) NOT NULL,
  source_version integer NOT NULL,
  updated_at timestamptz NOT NULL
);

CREATE TABLE authorization_context_projection (
  account_id uuid NOT NULL,
  role_code varchar(80) NOT NULL,
  status varchar(20) NOT NULL,
  source_version integer NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (account_id, role_code)
);

CREATE TABLE system_access_context_projection (
  account_id uuid NOT NULL,
  system_code varchar(80) NOT NULL,
  status varchar(20) NOT NULL,
  source_version integer NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (account_id, system_code)
);

CREATE TABLE processed_messages (
  consumer_name varchar(120) NOT NULL,
  message_id uuid NOT NULL,
  correlation_id uuid,
  message_type varchar(160),
  message_kind varchar(20),
  producer varchar(80),
  aggregate_id uuid,
  aggregate_version integer,
  envelope jsonb,
  processing_state varchar(30) NOT NULL DEFAULT 'APPLIED',
  reconciliation_state varchar(40),
  processed_at timestamptz NOT NULL,
  CHECK (processing_state IN ('DEFERRED', 'APPLIED')),
  CHECK (
    (processing_state = 'DEFERRED' AND reconciliation_state IN ('EVENT_PENDING', 'PROJECTION_RECONCILIATION'))
    OR (processing_state = 'APPLIED' AND reconciliation_state IS NULL)
  ),
  PRIMARY KEY (consumer_name, message_id)
);

CREATE TABLE idempotency_records (
  producer varchar(80) NOT NULL,
  idempotency_key varchar(200) NOT NULL,
  message_type varchar(160) NOT NULL,
  request_fingerprint varchar(128) NOT NULL,
  aggregate_id uuid,
  aggregate_version integer,
  result_status varchar(20) NOT NULL,
  error_code varchar(80),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (producer, idempotency_key)
);

CREATE TABLE outbox_messages (
  message_id uuid PRIMARY KEY,
  message_type varchar(160) NOT NULL,
  message_version integer NOT NULL,
  message_kind varchar(20) NOT NULL,
  aggregate_id uuid,
  aggregate_version integer,
  correlation_id uuid NOT NULL,
  causation_id uuid,
  payload jsonb NOT NULL,
  metadata jsonb,
  status outbox_status NOT NULL DEFAULT 'PENDING',
  attempts integer NOT NULL DEFAULT 0,
  claim_token uuid,
  lease_until timestamptz,
  occurred_at timestamptz NOT NULL,
  available_at timestamptz NOT NULL,
  published_at timestamptz,
  last_error_code varchar(80),
  last_error_at timestamptz,
  CHECK (
    (status = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (status <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL)
  )
);

CREATE INDEX ix_academic_outbox_pending
ON outbox_messages (available_at, occurred_at)
WHERE status = 'PENDING';

CREATE INDEX ix_academic_outbox_processing_lease
ON outbox_messages (lease_until)
WHERE status = 'PROCESSING';

CREATE INDEX ix_eligibility_profile_version
ON student_academic_eligibilities (student_academic_profile_id, eligibility_version);

CREATE INDEX ix_course_sections_period
ON course_sections (academic_period_id);

CREATE INDEX ix_student_course_sections_course
ON student_course_sections (course_section_id);

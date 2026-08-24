-- PR4 typed projection destination. Every row carries the version of its owner stream;
-- no global Event version is used.

ALTER TABLE projection_recovery_jobs
  ADD COLUMN current_query_message_id UUID;

CREATE INDEX projection_recovery_jobs_query_idx
  ON projection_recovery_jobs(current_query_message_id)
  WHERE current_query_message_id IS NOT NULL;

CREATE TABLE projection_recovery_messages (
    message_id UUID PRIMARY KEY,
    recovery_job_id UUID NOT NULL REFERENCES projection_recovery_jobs(recovery_job_id),
    generation BIGINT NOT NULL,
    message_type VARCHAR(160) NOT NULL,
    message_version INTEGER NOT NULL,
    exchange_name VARCHAR(160) NOT NULL,
    routing_key VARCHAR(160) NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID,
    idempotency_key VARCHAR(200) NOT NULL,
    actor JSONB,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    claim_token UUID,
    claim_until TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error_code VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT recovery_message_status_ck CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT recovery_message_exchange_ck CHECK (exchange_name = 'platform.queries')
);

CREATE INDEX projection_recovery_messages_claim_idx
  ON projection_recovery_messages(status, next_attempt_at, created_at);

CREATE TABLE person_presentation_read_model (
    person_id UUID PRIMARY KEY,
    first_name VARCHAR(160),
    paternal_surname VARCHAR(160),
    maternal_surname VARCHAR(160),
    institutional_email VARCHAR(320),
    status VARCHAR(32),
    source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE academic_period_read_model (
    academic_period_id UUID PRIMARY KEY,
    code VARCHAR(80), name VARCHAR(240), start_date DATE, end_date DATE,
    status VARCHAR(32), academic_data_status VARCHAR(32),
    source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE student_academic_profile_read_model (
    student_academic_profile_id UUID PRIMARY KEY,
    person_id UUID, account_id UUID NOT NULL, student_number VARCHAR(80),
    current_semester INTEGER, academic_status VARCHAR(32),
    source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE student_academic_eligibility_read_model (
    eligibility_id UUID NOT NULL,
    student_academic_profile_id UUID NOT NULL,
    system_code VARCHAR(160) NOT NULL,
    eligible BOOLEAN NOT NULL,
    policy_version VARCHAR(160) NOT NULL,
    eligibility_version BIGINT NOT NULL,
    academic_profile_version BIGINT NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_academic_profile_id, system_code),
    UNIQUE (eligibility_id)
);

CREATE TABLE course_section_read_model (
    course_section_id UUID PRIMARY KEY,
    academic_period_id UUID NOT NULL, nrc VARCHAR(80), name VARCHAR(240), status VARCHAR(32),
    source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE student_enrollment_read_model (
    student_academic_profile_id UUID NOT NULL,
    account_id UUID NOT NULL, course_section_id UUID NOT NULL, academic_period_id UUID NOT NULL,
    nrc VARCHAR(80), status VARCHAR(32) NOT NULL, source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (student_academic_profile_id, course_section_id)
);

CREATE TABLE student_import_read_model (
    import_id UUID PRIMARY KEY, academic_period_id UUID, status VARCHAR(32),
    started_by_account_id UUID, authorized_by_account_id UUID,
    started_at TIMESTAMPTZ, validated_at TIMESTAMPTZ, application_started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ, failed_at TIMESTAMPTZ, summary JSONB,
    application_progress JSONB, validation_error_count INTEGER NOT NULL DEFAULT 0,
    execution_generation BIGINT NOT NULL DEFAULT 0, last_resumed_by_account_id UUID,
    last_resumed_at TIMESTAMPTZ, failed_step VARCHAR(160), failure_reason VARCHAR(500),
    recoverable BOOLEAN NOT NULL DEFAULT FALSE, process_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE student_import_validation_error_read_model (
    import_id UUID NOT NULL, batch_number INTEGER NOT NULL, batch_count INTEGER NOT NULL,
    sequence BIGINT NOT NULL, sheet VARCHAR(160), row_number BIGINT NOT NULL,
    column_name VARCHAR(160), error_code VARCHAR(160) NOT NULL, error_message TEXT NOT NULL,
    process_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (import_id, batch_number, sequence),
    CONSTRAINT import_error_batch_ck CHECK (batch_number > 0 AND batch_count >= batch_number)
);

CREATE TABLE mailbox_messages_read_model (
    inbox_message_id UUID PRIMARY KEY, communication_id UUID NOT NULL,
    recipient_account_id UUID NOT NULL, system_code VARCHAR(160) NOT NULL,
    communication_type VARCHAR(160) NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ, source_version BIGINT NOT NULL
);

CREATE TABLE mailbox_visits_read_model (
    account_id UUID NOT NULL, system_code VARCHAR(160) NOT NULL,
    last_visited_at TIMESTAMPTZ NOT NULL, source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (account_id, system_code)
);

CREATE TABLE wash_service_calendar_read_model (
    calendar_id UUID PRIMARY KEY, code VARCHAR(80), name VARCHAR(240), timezone VARCHAR(80),
    effective_from DATE, effective_until DATE, status VARCHAR(32), source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE wash_service_schedule_read_model (
    schedule_id UUID PRIMARY KEY, calendar_id UUID, day_of_week INTEGER,
    opening_time TIME, closing_time TIME, slot_duration_minutes INTEGER, status VARCHAR(32),
    source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE wash_administrative_closure_read_model (
    closure_id UUID PRIMARY KEY, calendar_id UUID, scope VARCHAR(32), cabin_id UUID, tank_id UUID,
    starts_at TIMESTAMPTZ, ends_at TIMESTAMPTZ, reason VARCHAR(500), status VARCHAR(32),
    source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE wash_cabin_read_model (
    cabin_id UUID PRIMARY KEY, code VARCHAR(80), name VARCHAR(240), cabin_type VARCHAR(80),
    status VARCHAR(32), source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE wash_tank_read_model (
    tank_id UUID PRIMARY KEY, cabin_id UUID, code VARCHAR(80), name VARCHAR(240),
    configured_capacity INTEGER, status VARCHAR(32), source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE appointment_time_slot_read_model (
    appointment_time_slot_id UUID PRIMARY KEY, calendar_id UUID, source_schedule_id UUID,
    service_date DATE, starts_at TIMESTAMPTZ, ends_at TIMESTAMPTZ, timezone VARCHAR(80),
    booking_deadline_at TIMESTAMPTZ, cancellation_deadline_at TIMESTAMPTZ,
    arrival_tolerance_until TIMESTAMPTZ, status VARCHAR(32),
    capacity_normal INTEGER, capacity_journey INTEGER, capacity_immunocompromised INTEGER,
    slot_source_version BIGINT NOT NULL DEFAULT 0,
    capacity_projection_version BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE appointment_read_model (
    appointment_id UUID PRIMARY KEY, appointment_time_slot_id UUID NOT NULL,
    student_account_id UUID NOT NULL, appointment_type VARCHAR(80), instrument_count INTEGER,
    piece_type VARCHAR(160), course_section_id UUID, status VARCHAR(32) NOT NULL,
    cancellation_type VARCHAR(80), source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE exceptional_appointment_authorization_read_model (
    authorization_id UUID PRIMARY KEY, student_account_id UUID NOT NULL, service_date DATE,
    status VARCHAR(32) NOT NULL, authorized_by_account_id UUID, reason VARCHAR(500),
    source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE wash_execution_read_model (
    wash_execution_id UUID PRIMARY KEY, appointment_id UUID, appointment_type VARCHAR(80),
    status VARCHAR(32) NOT NULL, arrived_at TIMESTAMPTZ, authorized_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ, exit_submitted_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ, cancellation_type VARCHAR(80), execution_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE wash_resource_assignment_read_model (
    resource_assignment_id UUID PRIMARY KEY, wash_execution_id UUID NOT NULL,
    appointment_id UUID, appointment_time_slot_id UUID, cabin_id UUID, tank_id UUID,
    assignment_type VARCHAR(80), assigned_at TIMESTAMPTZ, released_at TIMESTAMPTZ,
    source_message_id UUID NOT NULL, source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE wash_operational_resource_unavailability_read_model (
    resource_unavailability_id UUID PRIMARY KEY, resource_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL, status VARCHAR(32) NOT NULL, reason VARCHAR(500),
    source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE wash_cleaning_period_read_model (
    cleaning_period_id UUID PRIMARY KEY, wash_execution_id UUID NOT NULL, resource_assignment_id UUID,
    cabin_id UUID, tank_id UUID, starts_at TIMESTAMPTZ, expected_ends_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ, status VARCHAR(32) NOT NULL, source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX student_import_validation_error_batch_idx
    ON student_import_validation_error_read_model(import_id, batch_number);
CREATE INDEX wash_execution_pending_reassignment_idx
    ON wash_execution_read_model(status, authorized_at, wash_execution_id);

INSERT INTO projection_status(projection_name, status, generation, updated_at)
VALUES
  ('person', 'BOOTSTRAPPING', 0, CURRENT_TIMESTAMP),
  ('revocation', 'BOOTSTRAPPING', 0, CURRENT_TIMESTAMP),
  ('authorization', 'BOOTSTRAPPING', 0, CURRENT_TIMESTAMP),
  ('academic', 'BOOTSTRAPPING', 0, CURRENT_TIMESTAMP),
  ('communications', 'BOOTSTRAPPING', 0, CURRENT_TIMESTAMP),
  ('wash-administration', 'BOOTSTRAPPING', 0, CURRENT_TIMESTAMP),
  ('appointments', 'BOOTSTRAPPING', 0, CURRENT_TIMESTAMP),
  ('wash-execution', 'BOOTSTRAPPING', 0, CURRENT_TIMESTAMP)
ON CONFLICT (projection_name) DO NOTHING;

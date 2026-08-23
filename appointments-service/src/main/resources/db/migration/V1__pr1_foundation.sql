-- PR1 foundation. Cross-service identifiers are logical UUIDs and deliberately
-- have no foreign keys to another service's database.

CREATE TABLE appointment_time_slots (
    appointment_time_slot_id uuid PRIMARY KEY,
    source_schedule_id uuid NOT NULL,
    service_date date NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    timezone varchar(80) NOT NULL,
    booking_deadline_at timestamptz NOT NULL,
    cancellation_deadline_at timestamptz NOT NULL,
    arrival_tolerance_until timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_appointment_slots_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_appointment_slots_range CHECK (ends_at > starts_at),
    CONSTRAINT ck_appointment_slots_deadlines CHECK (booking_deadline_at <= starts_at AND cancellation_deadline_at <= booking_deadline_at AND arrival_tolerance_until >= starts_at),
    CONSTRAINT ck_appointment_slots_version CHECK (version > 0),
    CONSTRAINT uq_appointment_slots_equivalent UNIQUE (source_schedule_id, service_date, starts_at, ends_at)
);
CREATE INDEX ix_appointment_slots_date_status ON appointment_time_slots (service_date, status, starts_at);

CREATE TABLE exceptional_appointment_authorizations (
    exceptional_authorization_id uuid PRIMARY KEY,
    student_account_id uuid NOT NULL,
    service_date date NOT NULL,
    granted_at timestamptz NOT NULL,
    expires_at timestamptz,
    cancelled boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_exceptional_authorizations_version CHECK (version > 0),
    CONSTRAINT ck_exceptional_authorizations_expiry CHECK (expires_at IS NULL OR expires_at >= granted_at),
    CONSTRAINT uq_exceptional_authorizations_student_date UNIQUE (student_account_id, service_date)
);
CREATE INDEX ix_exceptional_authorizations_lookup ON exceptional_appointment_authorizations (student_account_id, service_date, cancelled);

CREATE TABLE appointments (
    appointment_id uuid PRIMARY KEY,
    appointment_time_slot_id uuid NOT NULL REFERENCES appointment_time_slots (appointment_time_slot_id),
    student_account_id uuid NOT NULL,
    course_section_id uuid,
    appointment_type varchar(30) NOT NULL,
    instrument_count integer NOT NULL,
    piece_type varchar(30) NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'SCHEDULED',
    entry_authorized_at timestamptz,
    exceptional_authorization_id uuid REFERENCES exceptional_appointment_authorizations (exceptional_authorization_id),
    cancellation_type varchar(20),
    cancellation_subreason varchar(50),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    CONSTRAINT ck_appointments_type CHECK (appointment_type IN ('NORMAL', 'JOURNEY', 'IMMUNOCOMPROMISED')),
    CONSTRAINT ck_appointments_piece CHECK (piece_type IN ('HIGH_SPEED', 'LOW_SPEED', 'CONTRA_ANGLE')),
    CONSTRAINT ck_appointments_status CHECK (status IN ('SCHEDULED', 'CANCELLED', 'MISSED', 'ENTRY_REJECTED', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_appointments_instrument_count CHECK (instrument_count > 0 AND ((appointment_type = 'JOURNEY' AND instrument_count <= 80) OR (appointment_type <> 'JOURNEY' AND instrument_count <= 40))),
    CONSTRAINT ck_appointments_version CHECK (version > 0),
    CONSTRAINT ck_appointments_cancellation CHECK (
      (status = 'CANCELLED' AND cancellation_type IS NOT NULL)
      OR (status <> 'CANCELLED' AND cancellation_type IS NULL AND cancellation_subreason IS NULL)
    ),
    CONSTRAINT ck_appointments_cancellation_metadata CHECK (
      (cancellation_type = 'CLINIC' AND cancellation_subreason = 'CAPACITY_LOSS')
      OR (cancellation_type = 'STUDENT' AND cancellation_subreason IS NULL)
      OR (cancellation_type IS NULL AND cancellation_subreason IS NULL)
    )
);
CREATE UNIQUE INDEX uq_appointments_active_student ON appointments (student_account_id)
  WHERE status IN ('SCHEDULED', 'IN_PROGRESS');
CREATE UNIQUE INDEX uq_appointments_active_exceptional_authorization ON appointments (exceptional_authorization_id)
  WHERE exceptional_authorization_id IS NOT NULL
    AND NOT (status = 'CANCELLED' AND cancellation_type = 'CLINIC');
CREATE INDEX ix_appointments_student_status ON appointments (student_account_id, status);
CREATE INDEX ix_appointments_slot_status ON appointments (appointment_time_slot_id, status);
CREATE INDEX ix_appointments_slot_created_desc ON appointments (appointment_time_slot_id, created_at DESC, appointment_id DESC);
CREATE INDEX ix_appointments_exceptional_authorization ON appointments (exceptional_authorization_id);

CREATE TABLE appointment_capacity_projections (
    appointment_time_slot_id uuid PRIMARY KEY REFERENCES appointment_time_slots (appointment_time_slot_id),
    normal_capacity integer NOT NULL DEFAULT 0,
    journey_capacity integer NOT NULL DEFAULT 0,
    immunocompromised_capacity integer NOT NULL DEFAULT 0,
    normal_demand integer NOT NULL DEFAULT 0,
    journey_demand integer NOT NULL DEFAULT 0,
    immunocompromised_demand integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 1,
    calculated_at timestamptz NOT NULL,
    CONSTRAINT ck_capacity_projection_nonnegative CHECK (normal_capacity >= 0 AND journey_capacity >= 0 AND immunocompromised_capacity >= 0 AND normal_demand >= 0 AND journey_demand >= 0 AND immunocompromised_demand >= 0),
    CONSTRAINT ck_capacity_projection_version CHECK (version > 0)
);

-- Local projections. Payload is retained as compact JSON because PR1 does not
-- yet own wire contracts, while source identity/version remain queryable.
CREATE TABLE academic_period_projection (
    source_id uuid PRIMARY KEY, source_version bigint NOT NULL, status varchar(40) NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE student_academic_profile_projection (
    source_id uuid PRIMARY KEY, student_account_id uuid NOT NULL, source_version bigint NOT NULL,
    status varchar(40) NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE student_course_section_projection (
    source_id uuid PRIMARY KEY, student_account_id uuid NOT NULL, course_section_id uuid NOT NULL,
    source_version bigint NOT NULL, status varchar(40) NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true,
    UNIQUE (student_account_id, course_section_id)
);
CREATE TABLE account_role_projection (
    source_id uuid NOT NULL, account_id uuid NOT NULL, role_code varchar(120) NOT NULL,
    source_version bigint NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true,
    PRIMARY KEY (source_id, role_code)
);
CREATE TABLE wash_system_access_projection (
    source_id uuid NOT NULL, account_id uuid NOT NULL, system_code varchar(120) NOT NULL,
    granted boolean NOT NULL, source_version bigint NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true, PRIMARY KEY (source_id, system_code)
);
CREATE TABLE service_calendar_projection (
    source_id uuid PRIMARY KEY, source_version bigint NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE service_schedule_projection (
    source_id uuid PRIMARY KEY, source_version bigint NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE administrative_closure_projection (
    source_id uuid PRIMARY KEY, source_version bigint NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE cabin_projection (
    source_id uuid PRIMARY KEY, source_version bigint NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE tank_projection (
    source_id uuid PRIMARY KEY, configured_capacity integer NOT NULL DEFAULT 0,
    source_version bigint NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_tank_capacity_nonnegative CHECK (configured_capacity >= 0)
);
CREATE TABLE resource_priority_projection (
    source_id uuid PRIMARY KEY, source_version bigint NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE resource_unavailability_projection (
    source_id uuid PRIMARY KEY, source_version bigint NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE cleaning_period_projection (
    source_id uuid PRIMARY KEY, source_version bigint NOT NULL, starts_at timestamptz NOT NULL, expected_ends_at timestamptz NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb, updated_at timestamptz NOT NULL, reliable boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_cleaning_period_range CHECK (expected_ends_at > starts_at)
);

CREATE TABLE wash_execution_evidence_projection (
    evidence_id uuid PRIMARY KEY,
    wash_execution_id uuid NOT NULL,
    appointment_id uuid NOT NULL REFERENCES appointments (appointment_id),
    arrived_at timestamptz,
    arrival_execution_version bigint,
    arrival_source_event_id uuid,
    entry_authorized_at timestamptz,
    entry_authorized_execution_version bigint,
    entry_authorized_source_event_id uuid,
    last_source_event_id uuid NOT NULL,
    last_source_event_type varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (wash_execution_id, appointment_id)
);
CREATE UNIQUE INDEX uq_execution_evidence_source_event ON wash_execution_evidence_projection (last_source_event_id);
CREATE INDEX ix_execution_evidence_appointment ON wash_execution_evidence_projection (appointment_id);

CREATE TABLE wash_execution_transition_projection (
    transition_id uuid PRIMARY KEY,
    wash_execution_id uuid NOT NULL,
    appointment_id uuid NOT NULL REFERENCES appointments (appointment_id),
    event_type varchar(120) NOT NULL,
    execution_version bigint NOT NULL,
    source_event_id uuid NOT NULL UNIQUE,
    state varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    applied_at timestamptz NOT NULL
);
CREATE INDEX ix_execution_transition_execution_version ON wash_execution_transition_projection (wash_execution_id, execution_version);
CREATE INDEX ix_execution_transition_appointment ON wash_execution_transition_projection (appointment_id, execution_version);

CREATE TABLE projection_recovery_state (
    projection_name varchar(160) PRIMARY KEY,
    status varchar(30) NOT NULL,
    source_version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL,
    last_error varchar(1000),
    version bigint NOT NULL DEFAULT 1,
    CONSTRAINT ck_recovery_status CHECK (status IN ('UNINITIALIZED', 'REBUILDING', 'READY', 'DEGRADED')),
    CONSTRAINT ck_recovery_version CHECK (version > 0)
);

CREATE TABLE processed_messages (
    message_id uuid PRIMARY KEY,
    message_type varchar(160) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'CLAIMED',
    received_at timestamptz NOT NULL,
    applied_at timestamptz,
    deferred_at timestamptz,
    last_error varchar(1000),
    CONSTRAINT ck_processed_messages_status CHECK (status IN ('CLAIMED', 'APPLIED', 'DEFERRED', 'FAILED'))
);
CREATE INDEX ix_processed_messages_status ON processed_messages (status, received_at);

CREATE TABLE command_idempotency_records (
    scope varchar(160) NOT NULL,
    idempotency_key varchar(300) NOT NULL,
    request_fingerprint varchar(128) NOT NULL,
    message_id uuid NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'IN_PROGRESS',
    outcome_json jsonb,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    PRIMARY KEY (scope, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'CONFLICT'))
);
CREATE UNIQUE INDEX uq_idempotency_message_id ON command_idempotency_records (message_id);

CREATE TABLE outbox_messages (
    message_id uuid PRIMARY KEY,
    message_type varchar(160) NOT NULL,
    message_version integer NOT NULL,
    message_kind varchar(20) NOT NULL,
    aggregate_id uuid,
    aggregate_version bigint,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    payload jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    occurred_at timestamptz NOT NULL,
    available_at timestamptz NOT NULL,
    claimed_by varchar(200),
    lease_until timestamptz,
    published_at timestamptz,
    last_error varchar(1000),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_version CHECK (message_version > 0)
);
CREATE INDEX ix_outbox_claim ON outbox_messages (status, available_at, occurred_at);
CREATE INDEX ix_outbox_lease ON outbox_messages (claimed_by, lease_until);

CREATE TABLE pending_wash_execution_events (
    pending_event_id uuid PRIMARY KEY,
    source_event_id uuid NOT NULL UNIQUE,
    appointment_id uuid NOT NULL,
    wash_execution_id uuid NOT NULL,
    event_type varchar(120) NOT NULL,
    execution_version bigint NOT NULL,
    payload jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL,
    claimed_by varchar(200),
    lease_until timestamptz,
    completed_at timestamptz,
    CONSTRAINT ck_pending_execution_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX ix_pending_execution_claim ON pending_wash_execution_events (status, available_at);

CREATE TABLE pending_capacity_evaluation (
    evaluation_id uuid PRIMARY KEY,
    appointment_time_slot_id uuid NOT NULL REFERENCES appointment_time_slots (appointment_time_slot_id),
    cause varchar(160) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL,
    claimed_by varchar(200),
    lease_until timestamptz,
    completed_at timestamptz,
    CONSTRAINT ck_pending_capacity_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);
CREATE UNIQUE INDEX uq_pending_capacity_open_slot ON pending_capacity_evaluation (appointment_time_slot_id)
  WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX ix_pending_capacity_claim ON pending_capacity_evaluation (status, available_at);

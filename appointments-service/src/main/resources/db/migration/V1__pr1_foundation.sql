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
    CONSTRAINT ck_appointment_slots_deadlines CHECK (
      booking_deadline_at <= starts_at
      AND cancellation_deadline_at <= booking_deadline_at
      AND arrival_tolerance_until >= starts_at
    ),
    CONSTRAINT ck_appointment_slots_version CHECK (version > 0),
    CONSTRAINT uq_appointment_slots_equivalent UNIQUE (source_schedule_id, service_date, starts_at, ends_at)
);
CREATE INDEX ix_appointment_slots_date_status ON appointment_time_slots (service_date, status, starts_at);

CREATE TABLE exceptional_appointment_authorizations (
    exceptional_authorization_id uuid PRIMARY KEY,
    student_account_id uuid NOT NULL,
    service_date date NOT NULL,
    authorized_by_account_id uuid NOT NULL,
    reason varchar(1000) NOT NULL,
    granted_at timestamptz NOT NULL,
    expires_at timestamptz,
    cancelled_by_account_id uuid,
    cancellation_reason varchar(1000),
    cancelled_at timestamptz,
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_exceptional_authorizations_version CHECK (version > 0),
    CONSTRAINT ck_exceptional_authorizations_expiry CHECK (expires_at IS NULL OR expires_at >= granted_at),
    CONSTRAINT ck_exceptional_authorizations_cancellation CHECK (
      (cancelled_at IS NULL AND cancelled_by_account_id IS NULL AND cancellation_reason IS NULL)
      OR (cancelled_at IS NOT NULL AND cancelled_by_account_id IS NOT NULL AND cancellation_reason IS NOT NULL)
    ),
    CONSTRAINT uq_exceptional_authorizations_student_date UNIQUE (student_account_id, service_date)
);
CREATE INDEX ix_exceptional_authorizations_lookup ON exceptional_appointment_authorizations (student_account_id, service_date, cancelled_at);

CREATE TABLE appointments (
    appointment_id uuid PRIMARY KEY,
    appointment_time_slot_id uuid NOT NULL REFERENCES appointment_time_slots (appointment_time_slot_id),
    student_account_id uuid NOT NULL,
    course_section_id uuid NOT NULL,
    appointment_type varchar(30) NOT NULL,
    instrument_count integer NOT NULL,
    piece_type varchar(30) NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'SCHEDULED',
    entry_authorized_at timestamptz,
    exceptional_authorization_id uuid REFERENCES exceptional_appointment_authorizations (exceptional_authorization_id),
    cancellation_type varchar(20),
    cancellation_subreason varchar(50),
    cancellation_reason varchar(1000),
    cancelled_by_account_id uuid,
    cancelled_at timestamptz,
    rejection_reason varchar(1000),
    rejected_by_account_id uuid,
    rejected_at timestamptz,
    missed_at timestamptz,
    started_at timestamptz,
    completed_by_account_id uuid,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    CONSTRAINT ck_appointments_type CHECK (appointment_type IN ('NORMAL', 'JOURNEY', 'IMMUNOCOMPROMISED')),
    CONSTRAINT ck_appointments_piece CHECK (piece_type IN ('HIGH_SPEED', 'LOW_SPEED', 'CONTRA_ANGLE')),
    CONSTRAINT ck_appointments_status CHECK (status IN ('SCHEDULED', 'CANCELLED', 'MISSED', 'ENTRY_REJECTED', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_appointments_instrument_count CHECK (
      instrument_count > 0
      AND ((appointment_type = 'JOURNEY' AND instrument_count <= 80)
        OR (appointment_type <> 'JOURNEY' AND instrument_count <= 40))
    ),
    CONSTRAINT ck_appointments_version CHECK (version > 0),
    CONSTRAINT ck_appointments_cancellation CHECK (
      (status = 'CANCELLED' AND cancellation_type IS NOT NULL AND cancelled_at IS NOT NULL)
      OR (status <> 'CANCELLED' AND cancellation_type IS NULL AND cancellation_subreason IS NULL
          AND cancellation_reason IS NULL AND cancelled_by_account_id IS NULL AND cancelled_at IS NULL)
    ),
    CONSTRAINT ck_appointments_cancellation_metadata CHECK (
      (cancellation_type = 'CLINIC' AND cancellation_subreason = 'CAPACITY_LOSS')
      OR (cancellation_type = 'STUDENT' AND cancellation_subreason IS NULL)
      OR (cancellation_type IS NULL AND cancellation_subreason IS NULL)
    ),
    CONSTRAINT ck_appointments_rejection CHECK (
      (status = 'ENTRY_REJECTED' AND rejection_reason IS NOT NULL AND rejected_at IS NOT NULL)
      OR (status <> 'ENTRY_REJECTED' AND rejection_reason IS NULL AND rejected_by_account_id IS NULL AND rejected_at IS NULL)
    ),
    CONSTRAINT ck_appointments_missed CHECK (
      (status = 'MISSED' AND missed_at IS NOT NULL)
      OR (status <> 'MISSED' AND missed_at IS NULL)
    ),
    CONSTRAINT ck_appointments_started CHECK (
      (status IN ('IN_PROGRESS', 'COMPLETED') AND started_at IS NOT NULL)
      OR (status NOT IN ('IN_PROGRESS', 'COMPLETED') AND started_at IS NULL)
    ),
    CONSTRAINT ck_appointments_completed CHECK (
      (status = 'COMPLETED' AND completed_at IS NOT NULL)
      OR (status <> 'COMPLETED' AND completed_by_account_id IS NULL AND completed_at IS NULL)
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

-- Capacity projection stores only total usable capacity. Demand is derived from
-- appointments in SCHEDULED/IN_PROGRESS inside the deciding transaction.
CREATE TABLE appointment_capacity_projections (
    appointment_time_slot_id uuid PRIMARY KEY REFERENCES appointment_time_slots (appointment_time_slot_id),
    normal_capacity integer NOT NULL DEFAULT 0,
    journey_capacity integer NOT NULL DEFAULT 0,
    immunocompromised_capacity integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 1,
    calculated_at timestamptz NOT NULL,
    CONSTRAINT ck_capacity_projection_nonnegative CHECK (
      normal_capacity >= 0 AND journey_capacity >= 0 AND immunocompromised_capacity >= 0
    ),
    CONSTRAINT ck_capacity_projection_version CHECK (version > 0)
);

-- Academic projections use semantic fields required by ScheduleAppointment.
CREATE TABLE academic_period_projection (
    academic_period_id uuid PRIMARY KEY,
    code varchar(120) NOT NULL,
    status varchar(40) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE student_academic_profile_projection (
    academic_profile_id uuid PRIMARY KEY,
    student_account_id uuid NOT NULL UNIQUE,
    current_semester integer NOT NULL,
    academic_status varchar(40) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE student_course_section_projection (
    academic_profile_id uuid NOT NULL,
    student_account_id uuid NOT NULL,
    course_section_id uuid NOT NULL,
    academic_period_id uuid NOT NULL,
    enrolled_at timestamptz NOT NULL,
    removed_at timestamptz,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true,
    PRIMARY KEY (academic_profile_id, course_section_id),
    UNIQUE (student_account_id, course_section_id)
);

-- Authorization owner identity/version are preserved independently from lookup keys.
CREATE TABLE account_role_projection (
    account_id uuid PRIMARY KEY,
    role_id uuid NOT NULL,
    role_code varchar(120) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE wash_system_access_projection (
    system_access_id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    system_id uuid NOT NULL,
    system_code varchar(120) NOT NULL,
    access_status varchar(30) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true,
    UNIQUE (account_id, system_code),
    CONSTRAINT ck_wash_system_access_status CHECK (access_status IN ('ACTIVE', 'SUSPENDED', 'REVOKED'))
);

-- Wash Administration projections contain only canonical semantic fields.
CREATE TABLE service_calendar_projection (
    calendar_id uuid PRIMARY KEY,
    code varchar(120) NOT NULL,
    timezone varchar(80) NOT NULL,
    effective_from date NOT NULL,
    effective_until date,
    status varchar(30) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE service_schedule_projection (
    schedule_id uuid PRIMARY KEY,
    calendar_id uuid NOT NULL,
    day_of_week integer NOT NULL,
    opening_time time NOT NULL,
    closing_time time NOT NULL,
    slot_duration_minutes integer NOT NULL,
    status varchar(30) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_service_schedule_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_service_schedule_time CHECK (closing_time > opening_time),
    CONSTRAINT ck_service_schedule_duration CHECK (slot_duration_minutes > 0)
);
CREATE INDEX ix_service_schedule_calendar ON service_schedule_projection (calendar_id, day_of_week, status);

CREATE TABLE administrative_closure_projection (
    closure_id uuid PRIMARY KEY,
    calendar_id uuid NOT NULL,
    scope varchar(30) NOT NULL,
    cabin_id uuid,
    tank_id uuid,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    status varchar(30) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_admin_closure_range CHECK (ends_at > starts_at),
    CONSTRAINT ck_admin_closure_scope CHECK (
      (scope = 'SERVICE' AND cabin_id IS NULL AND tank_id IS NULL)
      OR (scope = 'CABIN' AND cabin_id IS NOT NULL AND tank_id IS NULL)
      OR (scope = 'TANK' AND cabin_id IS NULL AND tank_id IS NOT NULL)
    )
);
CREATE INDEX ix_admin_closure_window ON administrative_closure_projection (calendar_id, starts_at, ends_at, status);

CREATE TABLE cabin_projection (
    cabin_id uuid PRIMARY KEY,
    cabin_type varchar(40) NOT NULL,
    administrative_status varchar(30) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true
);
CREATE TABLE tank_projection (
    tank_id uuid PRIMARY KEY,
    cabin_id uuid NOT NULL,
    configured_capacity integer NOT NULL,
    administrative_status varchar(30) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_tank_capacity_nonnegative CHECK (configured_capacity >= 0)
);
CREATE INDEX ix_tank_cabin_status ON tank_projection (cabin_id, administrative_status);

CREATE TABLE resource_priority_projection (
    resource_priority_id uuid PRIMARY KEY,
    appointment_type varchar(40) NOT NULL,
    cabin_type varchar(40) NOT NULL,
    usage_mode varchar(40) NOT NULL,
    priority_order integer NOT NULL,
    status varchar(30) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_resource_priority_order CHECK (priority_order > 0)
);

-- Wash Execution operational projections are separate from Administration.
CREATE TABLE resource_unavailability_projection (
    resource_unavailability_id uuid PRIMARY KEY,
    cabin_id uuid,
    tank_id uuid,
    disabled_at timestamptz NOT NULL,
    restored_at timestamptz,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_resource_unavailability_target CHECK (
      (cabin_id IS NOT NULL AND tank_id IS NULL) OR (cabin_id IS NULL AND tank_id IS NOT NULL)
    )
);
CREATE TABLE cleaning_period_projection (
    cleaning_period_id uuid PRIMARY KEY,
    wash_execution_id uuid NOT NULL,
    resource_assignment_id uuid NOT NULL,
    cabin_id uuid NOT NULL,
    cleaning_trigger varchar(40) NOT NULL,
    starts_at timestamptz NOT NULL,
    expected_ends_at timestamptz NOT NULL,
    completed_at timestamptz,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    reliable boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_cleaning_period_range CHECK (expected_ends_at > starts_at)
);
CREATE INDEX ix_cleaning_period_window ON cleaning_period_projection (cabin_id, starts_at, expected_ends_at);

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
CREATE UNIQUE INDEX uq_execution_evidence_arrival_event ON wash_execution_evidence_projection (arrival_source_event_id) WHERE arrival_source_event_id IS NOT NULL;
CREATE UNIQUE INDEX uq_execution_evidence_entry_event ON wash_execution_evidence_projection (entry_authorized_source_event_id) WHERE entry_authorized_source_event_id IS NOT NULL;
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
    appointment_id uuid NOT NULL REFERENCES appointments (appointment_id),
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
CREATE INDEX ix_pending_execution_causal ON pending_wash_execution_events (wash_execution_id, execution_version, status);

CREATE TABLE pending_capacity_evaluation (
    evaluation_id uuid PRIMARY KEY,
    source_event_id uuid NOT NULL,
    appointment_time_slot_id uuid NOT NULL REFERENCES appointment_time_slots (appointment_time_slot_id),
    wash_execution_id uuid NOT NULL,
    appointment_id uuid NOT NULL REFERENCES appointments (appointment_id),
    cause varchar(160) NOT NULL,
    cleaning_trigger varchar(40),
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL,
    claimed_by varchar(200),
    lease_until timestamptz,
    completed_at timestamptz,
    CONSTRAINT ck_pending_capacity_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT uq_pending_capacity_causal UNIQUE (source_event_id, appointment_time_slot_id)
);
CREATE INDEX ix_pending_capacity_claim ON pending_capacity_evaluation (status, available_at);
CREATE INDEX ix_pending_capacity_execution ON pending_capacity_evaluation (wash_execution_id, status, available_at);

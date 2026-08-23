CREATE TABLE wash_executions (
    wash_execution_id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL UNIQUE,
    appointment_type varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    arrived_at timestamptz NOT NULL,
    started_at timestamptz,
    exit_submitted_by_account_id uuid,
    exit_submitted_at timestamptz,
    package_count integer,
    green_paper_cassette_8_count integer,
    green_paper_cassette_10_count integer,
    witness_tape_portion_count integer,
    completed_by_account_id uuid,
    completed_at timestamptz,
    cancellation_type varchar(32),
    cancellation_subreason varchar(160),
    cancellation_reason varchar(500),
    cancelled_by_account_id uuid,
    cancelled_at timestamptz,
    version integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_wash_executions_version_positive CHECK (version > 0),
    CONSTRAINT ck_wash_executions_status CHECK (status IN ('PENDING_ENTRY','ENTRY_REJECTED','PENDING_REASSIGNMENT','IN_PROGRESS','EXIT_SUBMITTED','COMPLETED','CANCELLED')),
    CONSTRAINT ck_wash_executions_materials_nonnegative CHECK (
      coalesce(package_count, 0) >= 0 AND coalesce(green_paper_cassette_8_count, 0) >= 0
      AND coalesce(green_paper_cassette_10_count, 0) >= 0 AND coalesce(witness_tape_portion_count, 0) >= 0),
    CONSTRAINT ck_wash_executions_exit_shape CHECK (
      (status IN ('EXIT_SUBMITTED','COMPLETED') AND exit_submitted_by_account_id IS NOT NULL
        AND exit_submitted_at IS NOT NULL AND package_count IS NOT NULL
        AND green_paper_cassette_8_count IS NOT NULL AND green_paper_cassette_10_count IS NOT NULL
        AND witness_tape_portion_count IS NOT NULL
        AND (package_count > 0 OR green_paper_cassette_8_count > 0 OR green_paper_cassette_10_count > 0 OR witness_tape_portion_count > 0))
      OR (status NOT IN ('EXIT_SUBMITTED','COMPLETED') AND exit_submitted_by_account_id IS NULL
        AND exit_submitted_at IS NULL AND package_count IS NULL AND green_paper_cassette_8_count IS NULL
        AND green_paper_cassette_10_count IS NULL AND witness_tape_portion_count IS NULL)),
    CONSTRAINT ck_wash_executions_completion_shape CHECK (
      (status = 'COMPLETED' AND completed_by_account_id IS NOT NULL AND completed_at IS NOT NULL)
      OR (status <> 'COMPLETED' AND completed_by_account_id IS NULL AND completed_at IS NULL)),
    CONSTRAINT ck_wash_executions_cancellation_shape CHECK (
      (status = 'CANCELLED' AND cancellation_type = 'CLINIC' AND btrim(coalesce(cancellation_subreason,'')) <> ''
        AND cancelled_by_account_id IS NOT NULL AND cancelled_at IS NOT NULL)
      OR (status <> 'CANCELLED' AND cancellation_type IS NULL AND cancellation_subreason IS NULL
        AND cancellation_reason IS NULL AND cancelled_by_account_id IS NULL AND cancelled_at IS NULL))
);

CREATE TABLE entry_validations (
    entry_validation_id uuid PRIMARY KEY,
    wash_execution_id uuid NOT NULL UNIQUE REFERENCES wash_executions(wash_execution_id),
    identity_confirmed boolean NOT NULL,
    requirements_satisfied boolean NOT NULL,
    decision varchar(16) NOT NULL,
    rejection_reason varchar(500),
    decided_by_account_id uuid NOT NULL,
    decided_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_entry_validations_decision CHECK (decision IN ('AUTHORIZED','REJECTED')),
    CONSTRAINT ck_entry_validations_values CHECK (
      (decision = 'AUTHORIZED' AND identity_confirmed AND requirements_satisfied AND rejection_reason IS NULL)
      OR (decision = 'REJECTED' AND btrim(coalesce(rejection_reason, '')) <> ''))
);

CREATE TABLE resource_assignments (
    resource_assignment_id uuid PRIMARY KEY,
    wash_execution_id uuid NOT NULL REFERENCES wash_executions(wash_execution_id),
    appointment_time_slot_id uuid NOT NULL,
    cabin_id uuid NOT NULL,
    tank_id uuid NOT NULL,
    assignment_type varchar(16) NOT NULL,
    assigned_by_account_id uuid,
    assigned_at timestamptz NOT NULL,
    released_by_account_id uuid,
    released_at timestamptz,
    release_reason varchar(32),
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_resource_assignments_type CHECK (assignment_type IN ('INITIAL','REASSIGNMENT')),
    CONSTRAINT ck_resource_assignments_reassignment_actor CHECK (assignment_type <> 'REASSIGNMENT' OR assigned_by_account_id IS NOT NULL),
    CONSTRAINT ck_resource_assignments_release CHECK (
      (released_at IS NULL AND release_reason IS NULL AND released_by_account_id IS NULL)
      OR (released_at IS NOT NULL AND release_reason IS NOT NULL AND released_at >= assigned_at))
);
CREATE UNIQUE INDEX ux_resource_assignments_active_execution ON resource_assignments(wash_execution_id) WHERE released_at IS NULL;
CREATE INDEX ix_resource_assignments_tank_slot_active ON resource_assignments(tank_id, appointment_time_slot_id) WHERE released_at IS NULL;
CREATE INDEX ix_resource_assignments_resource_active ON resource_assignments(cabin_id, tank_id) WHERE released_at IS NULL;

CREATE TABLE resource_unavailabilities (
    resource_unavailability_id uuid PRIMARY KEY,
    detected_during_wash_execution_id uuid REFERENCES wash_executions(wash_execution_id),
    cabin_id uuid,
    tank_id uuid,
    cause_type varchar(32) NOT NULL,
    reason varchar(500) NOT NULL,
    disabled_by_account_id uuid NOT NULL,
    disabled_at timestamptz NOT NULL,
    restored_by_account_id uuid,
    restored_at timestamptz,
    resolution varchar(500),
    version integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_resource_unavailabilities_one_resource CHECK ((cabin_id IS NULL) <> (tank_id IS NULL)),
    CONSTRAINT ck_resource_unavailabilities_cause CHECK (cause_type IN ('FAILURE','MANUAL_DISABLE')),
    CONSTRAINT ck_resource_unavailabilities_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_resource_unavailabilities_version CHECK (version > 0),
    CONSTRAINT ck_resource_unavailabilities_restore CHECK (
      (restored_at IS NULL AND restored_by_account_id IS NULL AND resolution IS NULL)
      OR (restored_at IS NOT NULL AND restored_by_account_id IS NOT NULL AND restored_at >= disabled_at))
);
CREATE UNIQUE INDEX ux_resource_unavailabilities_active_cabin ON resource_unavailabilities(cabin_id)
  WHERE restored_at IS NULL AND cabin_id IS NOT NULL;
CREATE UNIQUE INDEX ux_resource_unavailabilities_active_tank ON resource_unavailabilities(tank_id)
  WHERE restored_at IS NULL AND tank_id IS NOT NULL;
CREATE INDEX ix_resource_unavailabilities_cabin_active ON resource_unavailabilities(cabin_id) WHERE restored_at IS NULL;
CREATE INDEX ix_resource_unavailabilities_tank_active ON resource_unavailabilities(tank_id) WHERE restored_at IS NULL;

CREATE TABLE resource_unavailability_affected_executions (
    resource_unavailability_id uuid NOT NULL REFERENCES resource_unavailabilities(resource_unavailability_id),
    wash_execution_id uuid NOT NULL REFERENCES wash_executions(wash_execution_id),
    resource_assignment_id uuid NOT NULL REFERENCES resource_assignments(resource_assignment_id),
    affected_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(resource_unavailability_id, wash_execution_id, resource_assignment_id)
);

CREATE TABLE cleaning_periods (
    cleaning_period_id uuid PRIMARY KEY,
    wash_execution_id uuid NOT NULL REFERENCES wash_executions(wash_execution_id),
    resource_assignment_id uuid NOT NULL UNIQUE REFERENCES resource_assignments(resource_assignment_id),
    cabin_id uuid NOT NULL,
    cleaning_trigger varchar(32) NOT NULL,
    starts_at timestamptz NOT NULL,
    expected_ends_at timestamptz NOT NULL,
    completed_by_account_id uuid,
    completed_at timestamptz,
    version integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_cleaning_periods_trigger CHECK (cleaning_trigger IN ('REASSIGNMENT','NORMAL_COMPLETION','CLINIC_CANCELLATION')),
    CONSTRAINT ck_cleaning_periods_interval CHECK (expected_ends_at = starts_at + interval '3 hours'),
    CONSTRAINT ck_cleaning_periods_completion CHECK (completed_by_account_id IS NULL AND (completed_at IS NULL OR completed_at = expected_ends_at)),
    CONSTRAINT ck_cleaning_periods_version CHECK (version > 0)
);
CREATE INDEX ix_cleaning_periods_interval ON cleaning_periods(cabin_id, starts_at, expected_ends_at);

CREATE TABLE appointment_projection (
    appointment_id uuid PRIMARY KEY,
    student_account_id uuid NOT NULL,
    appointment_time_slot_id uuid NOT NULL,
    course_section_id uuid NOT NULL,
    appointment_type varchar(32) NOT NULL,
    instrument_count integer NOT NULL,
    piece_type varchar(64) NOT NULL,
    appointment_status varchar(32) NOT NULL,
    entry_authorized_at timestamptz,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_appointment_projection_instrument_count CHECK (instrument_count > 0),
    CONSTRAINT ck_appointment_projection_source_version CHECK (source_version > 0)
);

CREATE TABLE appointment_time_slot_projection (
    appointment_time_slot_id uuid PRIMARY KEY,
    service_date date NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    timezone varchar(80) NOT NULL,
    arrival_tolerance_until timestamptz NOT NULL,
    status varchar(32) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_slot_projection_interval CHECK (ends_at > starts_at),
    CONSTRAINT ck_slot_projection_source_version CHECK (source_version > 0)
);

CREATE TABLE cabin_projection (
    cabin_id uuid PRIMARY KEY,
    code varchar(80) NOT NULL,
    name varchar(160),
    cabin_type varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_cabin_projection_source_version CHECK (source_version > 0)
);

CREATE TABLE tank_projection (
    tank_id uuid PRIMARY KEY,
    cabin_id uuid NOT NULL,
    code varchar(80) NOT NULL,
    name varchar(160),
    configured_capacity integer NOT NULL,
    status varchar(32) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_tank_projection_capacity CHECK (configured_capacity > 0),
    CONSTRAINT ck_tank_projection_source_version CHECK (source_version > 0)
);
CREATE INDEX ix_tank_projection_cabin ON tank_projection(cabin_id);

CREATE TABLE resource_priority_projection (
    resource_priority_id uuid PRIMARY KEY,
    appointment_type varchar(32) NOT NULL,
    cabin_type varchar(32) NOT NULL,
    usage_mode varchar(64) NOT NULL,
    priority_order integer NOT NULL,
    status varchar(32) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_resource_priority_order CHECK (priority_order > 0),
    CONSTRAINT ck_resource_priority_source_version CHECK (source_version > 0),
    UNIQUE(appointment_type, cabin_type, usage_mode, priority_order)
);

CREATE TABLE administrative_closure_projection (
    administrative_closure_id uuid PRIMARY KEY,
    calendar_id uuid NOT NULL,
    scope varchar(32) NOT NULL,
    cabin_id uuid,
    tank_id uuid,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    status varchar(32) NOT NULL,
    source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_closure_projection_interval CHECK (ends_at > starts_at),
    CONSTRAINT ck_closure_projection_source_version CHECK (source_version > 0)
);
CREATE INDEX ix_administrative_closure_interval ON administrative_closure_projection(starts_at, ends_at);

CREATE TABLE authorization_role_projection (
    account_id uuid PRIMARY KEY,
    role_code varchar(100) NOT NULL,
    role_source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_authorization_role_source_version CHECK (role_source_version > 0)
);

CREATE TABLE authorization_access_projection (
    account_id uuid NOT NULL,
    system_id uuid NOT NULL,
    system_code varchar(100) NOT NULL,
    access_status varchar(32) NOT NULL,
    access_source_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY(account_id, system_code),
    CONSTRAINT ck_authorization_access_source_version CHECK (access_source_version > 0)
);

CREATE TABLE projection_recovery_state (
    projection_name varchar(120) PRIMARY KEY,
    source_logical_component_id varchar(120) NOT NULL,
    readiness_status varchar(32) NOT NULL,
    last_successful_rebuild_at timestamptz,
    last_error text,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_projection_recovery_status CHECK (readiness_status IN ('READY','REBUILDING','DEGRADED'))
);

CREATE TABLE processed_messages (
    consumer_name varchar(160) NOT NULL,
    message_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    message_type varchar(160) NOT NULL,
    message_version integer NOT NULL,
    message_kind varchar(20) NOT NULL,
    producer varchar(120) NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    operation_id uuid,
    idempotency_key varchar(200),
    aggregate_id uuid,
    aggregate_version bigint,
    actor_json jsonb,
    payload_json jsonb NOT NULL,
    received_at timestamptz NOT NULL,
    occurred_at timestamptz NOT NULL,
    applied_at timestamptz,
    deferred_reason varchar(200),
    updated_at timestamptz NOT NULL,
    claim_token uuid,
    lease_until timestamptz,
    PRIMARY KEY(consumer_name, message_id),
    CONSTRAINT ck_processed_message_status CHECK (status IN ('DEFERRED','APPLIED')),
    CONSTRAINT ck_processed_message_version CHECK (message_version > 0),
    CONSTRAINT ck_processed_message_kind CHECK (message_kind IN ('COMMAND','EVENT','RESULT','QUERY')),
    CONSTRAINT ck_processed_message_state CHECK (
      (status = 'DEFERRED' AND btrim(coalesce(deferred_reason,'')) <> '' AND applied_at IS NULL)
      OR (status = 'APPLIED' AND deferred_reason IS NULL AND applied_at IS NOT NULL)),
    CONSTRAINT ck_processed_message_claim CHECK (
      (claim_token IS NULL AND lease_until IS NULL)
      OR (status = 'DEFERRED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX ix_processed_messages_deferred_lease ON processed_messages(consumer_name, status, lease_until)
  WHERE status = 'DEFERRED';

CREATE TABLE outbox_messages (
    message_id uuid PRIMARY KEY,
    message_type varchar(160) NOT NULL,
    message_version integer NOT NULL,
    message_kind varchar(32) NOT NULL,
    producer varchar(120) NOT NULL,
    routing_key varchar(160) NOT NULL,
    occurred_at timestamptz NOT NULL,
    operation_id uuid,
    idempotency_key varchar(200),
    aggregate_id uuid,
    aggregate_version integer,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    actor_json jsonb,
    payload_json jsonb NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    last_error text,
    claim_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','PROCESSING','PUBLISHED','FAILED')),
    CONSTRAINT ck_outbox_message_version CHECK (message_version > 0),
    CONSTRAINT ck_outbox_claim CHECK (
      (status = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
      OR (status <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX ix_outbox_pending ON outbox_messages(status, available_at, created_at)
  WHERE status IN ('PENDING','PROCESSING');

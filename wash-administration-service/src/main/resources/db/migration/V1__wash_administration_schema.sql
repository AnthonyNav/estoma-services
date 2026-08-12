-- Functional and technical schema for PR1. Business bootstrap is deliberately
-- not performed here; ResourcePriority is provisioned by PR2.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TYPE service_calendar_status AS ENUM ('DRAFT', 'ACTIVE', 'INACTIVE');
CREATE TYPE service_schedule_status AS ENUM ('ACTIVE', 'INACTIVE');
CREATE TYPE administrative_closure_scope AS ENUM ('SERVICE', 'CABIN', 'TANK');
CREATE TYPE administrative_closure_status AS ENUM ('SCHEDULED', 'CANCELLED');
CREATE TYPE cabin_type AS ENUM ('NORMAL', 'JOURNEY', 'IMMUNOCOMPROMISED');
CREATE TYPE resource_status AS ENUM ('ACTIVE', 'INACTIVE', 'RETIRED');
CREATE TYPE appointment_type AS ENUM ('NORMAL', 'JOURNEY', 'IMMUNOCOMPROMISED');
CREATE TYPE usage_mode AS ENUM ('PREFERRED', 'ALTERNATIVE', 'EXCLUSIVE');
CREATE TYPE authorization_access_status AS ENUM ('ACTIVE', 'SUSPENDED', 'REVOKED');
CREATE TYPE idempotency_status AS ENUM ('IN_PROGRESS', 'SUCCEEDED', 'REJECTED', 'FAILED', 'EXPIRED');
CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED');

CREATE TABLE service_calendars (
    service_calendar_id uuid PRIMARY KEY,
    code varchar(80) NOT NULL,
    name varchar(160) NOT NULL,
    timezone varchar(80) NOT NULL,
    effective_from date NOT NULL,
    effective_until date,
    status service_calendar_status NOT NULL,
    version integer NOT NULL,
    created_by_account_id uuid NOT NULL,
    updated_by_account_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_service_calendars_code UNIQUE (code),
    CONSTRAINT ck_service_calendars_period CHECK (
        effective_until IS NULL OR effective_until >= effective_from
    ),
    CONSTRAINT ck_service_calendars_version_positive CHECK (version > 0),
    CONSTRAINT ck_service_calendars_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_service_calendars_timezone_not_blank CHECK (btrim(timezone) <> '')
);

ALTER TABLE service_calendars
    ADD CONSTRAINT ex_service_calendars_active_no_overlap
    EXCLUDE USING gist (
        daterange(effective_from, effective_until, '[]') WITH &&
    ) WHERE (status = 'ACTIVE');

CREATE TABLE service_schedules (
    service_schedule_id uuid PRIMARY KEY,
    service_calendar_id uuid NOT NULL REFERENCES service_calendars(service_calendar_id),
    day_of_week integer NOT NULL,
    opening_time time NOT NULL,
    closing_time time NOT NULL,
    slot_duration_minutes integer NOT NULL,
    status service_schedule_status NOT NULL,
    version integer NOT NULL,
    created_by_account_id uuid NOT NULL,
    updated_by_account_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_service_schedules_day_of_week CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_service_schedules_interval CHECK (opening_time < closing_time),
    CONSTRAINT ck_service_schedules_minute_precision CHECK (
        extract(second FROM opening_time) = 0 AND extract(second FROM closing_time) = 0
    ),
    CONSTRAINT ck_service_schedules_slot_duration_positive CHECK (slot_duration_minutes > 0),
    CONSTRAINT ck_service_schedules_complete_slots CHECK (
        (extract(epoch FROM (closing_time - opening_time)) / 60)::integer % slot_duration_minutes = 0
    ),
    CONSTRAINT ck_service_schedules_version_positive CHECK (version > 0)
);

ALTER TABLE service_schedules
    ADD CONSTRAINT ex_service_schedules_active_no_overlap
    EXCLUDE USING gist (
        service_calendar_id WITH =,
        day_of_week WITH =,
        int4range(
            (extract(epoch FROM opening_time) / 60)::integer,
            (extract(epoch FROM closing_time) / 60)::integer,
            '[)'
        ) WITH &&
    ) WHERE (status = 'ACTIVE');

CREATE INDEX ix_service_schedules_calendar ON service_schedules (service_calendar_id);

CREATE TABLE cabins (
    cabin_id uuid PRIMARY KEY,
    code varchar(80) NOT NULL,
    name varchar(160) NOT NULL,
    cabin_type cabin_type NOT NULL,
    status resource_status NOT NULL,
    version integer NOT NULL,
    created_by_account_id uuid NOT NULL,
    updated_by_account_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_cabins_code UNIQUE (code),
    CONSTRAINT ck_cabins_version_positive CHECK (version > 0),
    CONSTRAINT ck_cabins_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE tanks (
    tank_id uuid PRIMARY KEY,
    cabin_id uuid NOT NULL REFERENCES cabins(cabin_id),
    code varchar(80) NOT NULL,
    name varchar(160) NOT NULL,
    configured_capacity integer NOT NULL,
    status resource_status NOT NULL,
    version integer NOT NULL,
    created_by_account_id uuid NOT NULL,
    updated_by_account_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_tanks_cabin_code UNIQUE (cabin_id, code),
    CONSTRAINT ck_tanks_capacity_positive CHECK (configured_capacity > 0),
    CONSTRAINT ck_tanks_version_positive CHECK (version > 0),
    CONSTRAINT ck_tanks_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX ix_tanks_cabin ON tanks (cabin_id);

CREATE TABLE administrative_closures (
    administrative_closure_id uuid PRIMARY KEY,
    service_calendar_id uuid NOT NULL REFERENCES service_calendars(service_calendar_id),
    scope administrative_closure_scope NOT NULL,
    cabin_id uuid REFERENCES cabins(cabin_id),
    tank_id uuid REFERENCES tanks(tank_id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    reason varchar(500) NOT NULL,
    cancellation_reason varchar(500),
    status administrative_closure_status NOT NULL,
    version integer NOT NULL,
    created_by_account_id uuid NOT NULL,
    cancelled_by_account_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    cancelled_at timestamptz,
    CONSTRAINT ck_administrative_closures_interval CHECK (ends_at > starts_at),
    CONSTRAINT ck_administrative_closures_scope_refs CHECK (
        (scope = 'SERVICE' AND cabin_id IS NULL AND tank_id IS NULL)
        OR (scope = 'CABIN' AND cabin_id IS NOT NULL AND tank_id IS NULL)
        OR (scope = 'TANK' AND cabin_id IS NULL AND tank_id IS NOT NULL)
    ),
    CONSTRAINT ck_administrative_closures_reason_not_blank CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_administrative_closures_version_positive CHECK (version > 0),
    CONSTRAINT ck_administrative_closures_cancel_state CHECK (
        (status = 'SCHEDULED' AND cancelled_by_account_id IS NULL AND cancellation_reason IS NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_by_account_id IS NOT NULL AND btrim(cancellation_reason) <> '' AND cancelled_at IS NOT NULL)
    ),
    CONSTRAINT ux_administrative_closures_exact UNIQUE NULLS NOT DISTINCT (
        service_calendar_id, scope, cabin_id, tank_id, starts_at, ends_at, reason
    )
);

CREATE TABLE resource_priorities (
    resource_priority_id uuid PRIMARY KEY,
    appointment_type appointment_type NOT NULL,
    cabin_type cabin_type NOT NULL,
    usage_mode usage_mode NOT NULL,
    priority_order integer NOT NULL,
    status resource_status NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_resource_priorities_type_pair UNIQUE (appointment_type, cabin_type),
    CONSTRAINT ux_resource_priorities_appointment_order UNIQUE (appointment_type, priority_order),
    CONSTRAINT ck_resource_priorities_fixed_matrix CHECK (
        (appointment_type = 'NORMAL' AND cabin_type = 'NORMAL' AND usage_mode = 'PREFERRED' AND priority_order = 1)
        OR (appointment_type = 'NORMAL' AND cabin_type = 'JOURNEY' AND usage_mode = 'ALTERNATIVE' AND priority_order = 2)
        OR (appointment_type = 'JOURNEY' AND cabin_type = 'JOURNEY' AND usage_mode = 'PREFERRED' AND priority_order = 1)
        OR (appointment_type = 'JOURNEY' AND cabin_type = 'NORMAL' AND usage_mode = 'ALTERNATIVE' AND priority_order = 2)
        OR (appointment_type = 'IMMUNOCOMPROMISED' AND cabin_type = 'IMMUNOCOMPROMISED' AND usage_mode = 'EXCLUSIVE' AND priority_order = 1)
    ),
    CONSTRAINT ck_resource_priorities_active CHECK (status = 'ACTIVE'),
    CONSTRAINT ck_resource_priorities_version_positive CHECK (version > 0)
);

CREATE TABLE authorization_role_projection (
    account_id uuid PRIMARY KEY,
    role_code varchar(80) NOT NULL,
    source_version integer NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_authorization_role_projection_version_positive CHECK (source_version > 0)
);

CREATE TABLE authorization_access_projection (
    account_id uuid NOT NULL,
    system_code varchar(80) NOT NULL,
    access_status authorization_access_status NOT NULL,
    source_version integer NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (account_id, system_code),
    CONSTRAINT ck_authorization_access_projection_system CHECK (system_code = 'LAVADO_ULTRASONICO'),
    CONSTRAINT ck_authorization_access_projection_version_positive CHECK (source_version > 0)
);

CREATE TABLE processed_messages (
    consumer_name varchar(120) NOT NULL,
    message_id uuid NOT NULL,
    correlation_id uuid,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer_name, message_id)
);

CREATE INDEX ix_processed_messages_processed_at ON processed_messages (processed_at);

CREATE TABLE command_idempotency_records (
    producer varchar(120) NOT NULL,
    operation_type varchar(120) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    status idempotency_status NOT NULL DEFAULT 'IN_PROGRESS',
    result_status varchar(30),
    result_data jsonb,
    error_code varchar(80),
    aggregate_id uuid,
    aggregate_version integer,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    PRIMARY KEY (producer, operation_type, idempotency_key),
    CONSTRAINT ck_command_idempotency_terminal_fields CHECK (
        (status = 'IN_PROGRESS' AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'REJECTED', 'FAILED', 'EXPIRED') AND completed_at IS NOT NULL)
    )
);

CREATE INDEX ix_command_idempotency_completed_at ON command_idempotency_records (completed_at);

CREATE TABLE outbox_messages (
    message_id uuid PRIMARY KEY,
    message_type varchar(160) NOT NULL,
    message_version integer NOT NULL,
    message_kind varchar(20) NOT NULL,
    producer varchar(120) NOT NULL,
    routing_key varchar(200) NOT NULL,
    occurred_at timestamptz NOT NULL,
    operation_id uuid,
    idempotency_key varchar(200),
    aggregate_id uuid,
    aggregate_version integer,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    actor jsonb NOT NULL,
    payload jsonb NOT NULL,
    metadata jsonb,
    status outbox_status NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    claim_token uuid,
    lease_until timestamptz,
    available_at timestamptz NOT NULL,
    published_at timestamptz,
    last_error_code varchar(80),
    last_error_at timestamptz,
    CONSTRAINT ck_outbox_message_version_positive CHECK (message_version > 0),
    CONSTRAINT ck_outbox_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_actor_shape CHECK (
        actor ? 'type'
        AND (
            (actor->>'type' = 'SYSTEM' AND actor ? 'service')
            OR (actor->>'type' = 'ACCOUNT' AND actor ? 'accountId')
        )
    ),
    CONSTRAINT ck_outbox_claim_state CHECK (
        (status = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL)
    )
);

CREATE INDEX ix_outbox_pending ON outbox_messages (available_at, occurred_at)
    WHERE status = 'PENDING';
CREATE INDEX ix_outbox_processing_lease ON outbox_messages (lease_until)
    WHERE status = 'PROCESSING';
CREATE INDEX ix_outbox_aggregate ON outbox_messages (aggregate_id);

CREATE TABLE academic_period_projections (
    academic_period_id uuid PRIMARY KEY,
    code varchar(100),
    start_date date,
    end_date date,
    status varchar(20) NOT NULL,
    source_version integer NOT NULL CHECK (source_version > 0),
    source_occurred_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_communications_academic_period_status
        CHECK (status IN ('ACTIVE', 'CLOSED'))
);

CREATE TABLE academic_period_projection_state (
    state_key smallint PRIMARY KEY CHECK (state_key = 1),
    readiness varchar(20) NOT NULL,
    active_academic_period_id uuid,
    active_source_version integer,
    active_source_occurred_at timestamptz,
    recovery_query_message_id uuid,
    recovery_requested_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_communications_academic_period_readiness
        CHECK (readiness IN ('BOOTSTRAPPING', 'READY', 'DEGRADED')),
    CONSTRAINT ck_communications_academic_period_recovery
        CHECK (
            (recovery_query_message_id IS NULL AND recovery_requested_at IS NULL)
            OR (recovery_query_message_id IS NOT NULL AND recovery_requested_at IS NOT NULL)
        )
);

INSERT INTO academic_period_projection_state (
    state_key, readiness, active_academic_period_id, active_source_version, active_source_occurred_at,
    recovery_query_message_id, recovery_requested_at, updated_at
) VALUES (1, 'BOOTSTRAPPING', NULL, NULL, NULL, NULL, NULL, now());

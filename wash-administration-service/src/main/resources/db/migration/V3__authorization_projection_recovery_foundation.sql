CREATE TYPE projection_recovery_scope AS ENUM ('FULL', 'ENTITY');
CREATE TYPE projection_recovery_status AS ENUM ('PENDING', 'WAITING_RESULT', 'COMPLETED', 'FAILED');

CREATE TABLE projection_recovery_jobs (
    recovery_job_id uuid PRIMARY KEY,
    projection_type varchar(80) NOT NULL,
    scope projection_recovery_scope NOT NULL,
    entity_key varchar(240),
    status projection_recovery_status NOT NULL,
    cursor text,
    query_attempt integer NOT NULL DEFAULT 0,
    current_query_type varchar(160),
    current_query_message_id uuid,
    current_operation_id uuid,
    current_correlation_id uuid,
    attempts integer NOT NULL DEFAULT 0,
    last_error_code varchar(120),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    claim_token uuid,
    lease_until timestamptz,
    CONSTRAINT ck_projection_recovery_projection_type CHECK (projection_type = 'AUTHORIZATION'),
    CONSTRAINT ck_projection_recovery_entity_key CHECK (
        (scope = 'FULL' AND entity_key IS NULL)
        OR (scope = 'ENTITY' AND entity_key IS NOT NULL AND btrim(entity_key) <> '')
    ),
    CONSTRAINT ck_projection_recovery_attempts CHECK (query_attempt >= 0 AND attempts >= 0),
    CONSTRAINT ck_projection_recovery_claim CHECK (
        (claim_token IS NULL AND lease_until IS NULL)
        OR (claim_token IS NOT NULL AND lease_until IS NOT NULL)
    ),
    CONSTRAINT ck_projection_recovery_state_shape CHECK (
        (status = 'PENDING'
            AND current_query_type IS NULL
            AND current_query_message_id IS NULL
            AND current_operation_id IS NULL
            AND current_correlation_id IS NULL
            AND completed_at IS NULL)
        OR
        (status = 'WAITING_RESULT'
            AND current_query_type IS NOT NULL
            AND current_query_message_id IS NOT NULL
            AND current_operation_id IS NOT NULL
            AND current_correlation_id IS NOT NULL
            AND completed_at IS NULL
            AND claim_token IS NULL
            AND lease_until IS NULL)
        OR
        (status IN ('COMPLETED', 'FAILED')
            AND current_query_type IS NULL
            AND current_query_message_id IS NULL
            AND current_operation_id IS NULL
            AND current_correlation_id IS NULL
            AND completed_at IS NOT NULL
            AND claim_token IS NULL
            AND lease_until IS NULL)
    )
);

CREATE UNIQUE INDEX ux_projection_recovery_full_active
    ON projection_recovery_jobs (projection_type)
    WHERE scope = 'FULL' AND status IN ('PENDING', 'WAITING_RESULT');

CREATE UNIQUE INDEX ux_projection_recovery_entity_active
    ON projection_recovery_jobs (projection_type, entity_key)
    WHERE scope = 'ENTITY' AND status IN ('PENDING', 'WAITING_RESULT');

CREATE INDEX ix_projection_recovery_waiting_result
    ON projection_recovery_jobs (current_query_message_id)
    WHERE status = 'WAITING_RESULT';

CREATE INDEX ix_projection_recovery_claimable
    ON projection_recovery_jobs (status, updated_at)
    WHERE status = 'PENDING';

GRANT SELECT, INSERT, UPDATE, DELETE ON projection_recovery_jobs TO ${runtime_role};

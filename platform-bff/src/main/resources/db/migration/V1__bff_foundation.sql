CREATE TABLE client_operations (
    operation_id UUID PRIMARY KEY,
    actor_account_id UUID,
    public_operation VARCHAR(160) NOT NULL,
    command_type VARCHAR(160) NOT NULL,
    owner_logical_component_id VARCHAR(80) NOT NULL,
    command_routing_key VARCHAR(120) NOT NULL,
    correlation_id UUID NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    result_data JSONB,
    error_code VARCHAR(160),
    error_details JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    CONSTRAINT client_operations_status_ck CHECK (status IN ('PENDING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'EXPIRED'))
);

CREATE INDEX client_operations_actor_idx ON client_operations (actor_account_id, created_at DESC);

CREATE TABLE idempotency_records (
    scope_key VARCHAR(500) PRIMARY KEY,
    actor_account_id UUID,
    public_operation VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    operation_id UUID NOT NULL REFERENCES client_operations(operation_id),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idempotency_records_expiry_idx ON idempotency_records (expires_at);

CREATE TABLE outbox_messages (
    message_id UUID PRIMARY KEY,
    message_kind VARCHAR(16) NOT NULL,
    message_type VARCHAR(160) NOT NULL,
    message_version INTEGER NOT NULL,
    exchange_name VARCHAR(160) NOT NULL,
    routing_key VARCHAR(160) NOT NULL,
    operation_id UUID NOT NULL REFERENCES client_operations(operation_id),
    correlation_id UUID NOT NULL,
    causation_id UUID,
    aggregate_id UUID,
    aggregate_version INTEGER,
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
    CONSTRAINT outbox_kind_ck CHECK (message_kind = 'COMMAND'),
    CONSTRAINT outbox_exchange_ck CHECK (exchange_name = 'platform.commands'),
    CONSTRAINT outbox_status_ck CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX outbox_claim_idx ON outbox_messages (status, next_attempt_at, created_at);

CREATE TABLE inbox_messages (
    consumer_name VARCHAR(160) NOT NULL,
    message_id UUID NOT NULL,
    message_kind VARCHAR(16) NOT NULL,
    message_type VARCHAR(160) NOT NULL,
    message_version INTEGER NOT NULL,
    producer VARCHAR(120) NOT NULL,
    correlation_id UUID,
    causation_id UUID,
    operation_id UUID,
    aggregate_id UUID,
    aggregate_version INTEGER,
    received_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, message_id)
);

CREATE TABLE projection_status (
    projection_name VARCHAR(160) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    last_applied_at TIMESTAMPTZ,
    last_error_code VARCHAR(160),
    generation BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT projection_status_ck CHECK (status IN ('BOOTSTRAPPING', 'READY', 'DEGRADED', 'REBUILDING'))
);

CREATE TABLE projection_recovery_jobs (
    recovery_job_id UUID PRIMARY KEY,
    projection_name VARCHAR(160) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    entity_id UUID,
    generation BIGINT NOT NULL,
    owner_logical_component_id VARCHAR(80) NOT NULL,
    query_type VARCHAR(160) NOT NULL,
    operation_id UUID,
    cursor_value VARCHAR(500),
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(160),
    CONSTRAINT recovery_mode_ck CHECK (mode IN ('ENTITY', 'FULL')),
    CONSTRAINT recovery_entity_scope_ck CHECK (
        (mode = 'ENTITY' AND entity_id IS NOT NULL)
        OR (mode = 'FULL' AND entity_id IS NULL)
    )
);

CREATE TABLE account_person_projection (
    account_id UUID PRIMARY KEY,
    person_id UUID NOT NULL,
    source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE session_revocations (
    session_id UUID PRIMARY KEY,
    revoked_at_epoch_seconds BIGINT NOT NULL,
    reason_code VARCHAR(160) NOT NULL,
    source_message_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE account_session_revocation_cutoffs (
    account_id UUID PRIMARY KEY,
    revoked_before_or_at BIGINT NOT NULL,
    cutoff_kind VARCHAR(16) NOT NULL,
    except_session_id UUID,
    reason_code VARCHAR(160) NOT NULL,
    source_message_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT cutoff_kind_ck CHECK (cutoff_kind IN ('ALL', 'OTHER')),
    CONSTRAINT cutoff_exception_scope_ck CHECK (
        (cutoff_kind = 'ALL' AND except_session_id IS NULL)
        OR (cutoff_kind = 'OTHER' AND except_session_id IS NOT NULL)
    )
);

CREATE TABLE person_session_revocation_cutoffs (
    person_id UUID PRIMARY KEY,
    revoked_before_or_at BIGINT NOT NULL,
    reason_code VARCHAR(160) NOT NULL,
    source_message_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE authorization_role_projection (
    account_id UUID PRIMARY KEY,
    role_code VARCHAR(160),
    role_source_version BIGINT,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE authorization_access_projection (
    system_access_id UUID NOT NULL UNIQUE,
    account_id UUID NOT NULL,
    system_id UUID NOT NULL,
    system_code VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    access_source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, system_code)
);

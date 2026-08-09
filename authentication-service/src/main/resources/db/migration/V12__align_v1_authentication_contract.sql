-- Aligns the initial implementation schema with the platform Authentication V1
-- contract. This migration is additive so existing local databases remain valid.

ALTER TABLE accounts
    ADD COLUMN deactivation_reason_code varchar(40),
    ADD COLUMN last_recovery_requested_at timestamptz;

CREATE INDEX ix_accounts_locked_until ON accounts (locked_until);
CREATE INDEX ix_accounts_last_recovery_requested_at ON accounts (last_recovery_requested_at);

ALTER TABLE account_credentials
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false,
    ADD COLUMN temporary_password_expires_at timestamptz,
    ADD COLUMN temporary_password_consumed_at timestamptz,
    ADD COLUMN credential_version integer NOT NULL DEFAULT 1;

ALTER TABLE sessions
    ALTER COLUMN refresh_token_hash DROP NOT NULL,
    ADD COLUMN auth_state varchar(40) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN previous_refresh_token_hash varchar(500),
    ADD COLUMN previous_refresh_valid_until timestamptz,
    ADD COLUMN idle_expires_at timestamptz,
    ADD COLUMN absolute_expires_at timestamptz;

UPDATE sessions
SET idle_expires_at = expires_at,
    absolute_expires_at = expires_at
WHERE idle_expires_at IS NULL;

CREATE INDEX ix_sessions_idle_expires_at ON sessions (idle_expires_at);
CREATE INDEX ix_sessions_absolute_expires_at ON sessions (absolute_expires_at);
CREATE INDEX ix_sessions_previous_refresh_token_hash ON sessions (previous_refresh_token_hash);

ALTER TABLE password_recovery_requests
    ALTER COLUMN token_hash DROP NOT NULL,
    ADD COLUMN status varchar(40) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN invalidated_at timestamptz,
    ADD COLUMN last_attempt_at timestamptz,
    ADD COLUMN code_derivation_context varchar(80) NOT NULL DEFAULT 'legacy-sha256';

UPDATE password_recovery_requests
SET status = CASE
    WHEN used_at IS NOT NULL THEN 'USED'
    WHEN expires_at <= now() THEN 'EXPIRED'
    ELSE 'ACTIVE'
END,
    code_derivation_context = COALESCE(code_derivation_context, 'legacy-sha256')
WHERE code_derivation_context IS NULL;

CREATE UNIQUE INDEX uq_recovery_active_account
    ON password_recovery_requests (account_id)
    WHERE status = 'ACTIVE';

CREATE TABLE account_status_history (
    history_id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts (account_id),
    previous_status account_status,
    new_status account_status NOT NULL,
    reason_code varchar(40),
    reason_detail varchar(500),
    actor_type varchar(20) NOT NULL,
    actor_account_id uuid,
    actor_service varchar(80),
    correlation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    account_version integer NOT NULL
);

CREATE INDEX ix_account_status_history_account ON account_status_history (account_id);

CREATE TABLE command_idempotency_records (
    producer varchar(80) NOT NULL,
    operation_type varchar(80) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    request_fingerprint varchar(200) NOT NULL,
    target_account_id uuid,
    status varchar(20) NOT NULL,
    result_status varchar(20),
    result_data jsonb,
    error_code varchar(80),
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    PRIMARY KEY (producer, operation_type, idempotency_key)
);

ALTER TABLE processed_messages
    ADD COLUMN status varchar(20) NOT NULL DEFAULT 'APPLIED';

ALTER TABLE outbox_messages
    ADD COLUMN producer varchar(80) NOT NULL DEFAULT 'authentication',
    ADD COLUMN operation_id uuid,
    ADD COLUMN idempotency_key varchar(200),
    ADD COLUMN actor jsonb;

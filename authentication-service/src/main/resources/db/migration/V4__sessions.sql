-- sessions — one row per renewable session. A session is valid when
-- revoked_at IS NULL, expires_at is in the future, and both the account and
-- its person are active (checked at the application layer, not here).
--
-- Source: authentication-service/docs/system_contract.md ("Sessions" v1
-- parameters), platform docs data-model.md §7.

CREATE TABLE sessions (
    session_id             uuid PRIMARY KEY,
    account_id              uuid NOT NULL REFERENCES accounts (account_id),

    refresh_token_hash        varchar(500) NOT NULL,

    device_name                  varchar(160),
    ip_address                     varchar(45),
    user_agent                       varchar(300),

    created_at                         timestamptz NOT NULL,
    expires_at                           timestamptz NOT NULL,
    last_used_at                           timestamptz,
    revoked_at                               timestamptz,
    revocation_reason                          varchar(120),

    CONSTRAINT ck_sessions_expires_after_created
        CHECK (expires_at > created_at)
);

CREATE INDEX ix_sessions_account ON sessions (account_id);
CREATE INDEX ix_sessions_expires ON sessions (expires_at);
CREATE UNIQUE INDEX uq_sessions_refresh_token ON sessions (refresh_token_hash);

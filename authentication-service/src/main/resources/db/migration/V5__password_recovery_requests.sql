-- password_recovery_requests — one row per recovery attempt. Single-use,
-- expiring, attempt-limited.
--
-- Source: authentication-service/docs/system_contract.md ("Password
-- Recovery Parameters"), platform docs data-model.md §8.

CREATE TABLE password_recovery_requests (
    recovery_id       uuid PRIMARY KEY,
    account_id          uuid NOT NULL REFERENCES accounts (account_id),

    token_hash             varchar(500) NOT NULL,
    created_at                timestamptz NOT NULL,
    expires_at                   timestamptz NOT NULL,
    used_at                         timestamptz,
    failed_attempts                   integer NOT NULL DEFAULT 0,

    CONSTRAINT ck_recovery_expires_after_created
        CHECK (expires_at > created_at),
    CONSTRAINT ck_recovery_failed_attempts_non_negative
        CHECK (failed_attempts >= 0)
);

CREATE INDEX ix_recovery_account ON password_recovery_requests (account_id);
CREATE UNIQUE INDEX uq_recovery_token ON password_recovery_requests (token_hash);
CREATE INDEX ix_recovery_expires ON password_recovery_requests (expires_at);

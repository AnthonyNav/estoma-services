-- security_rpc_idempotency_records — protects idempotency for the
-- ephemeral security RPC operations that generate a secret
-- (SetTemporaryPassword, RegenerateTemporaryPassword). Not Consumer Inbox,
-- not Outbox: the ephemeral RPC path never uses either. Unlike the
-- student-number sync projection, this table's account_id is a real
-- physical reference, since it only ever protects operations against
-- accounts that already exist locally.
--
-- Source: authentication-service/docs/system_contract.md ("Mandatory
-- Invariants" #11), platform docs data-model.md §9.2.

CREATE TABLE security_rpc_idempotency_records (
    operation_type              varchar(60) NOT NULL,
    idempotency_key                varchar(200) NOT NULL,

    account_id                       uuid NOT NULL REFERENCES accounts (account_id),
    requested_by_account_id            uuid NOT NULL,
    request_fingerprint                  varchar(200) NOT NULL,

    status                                  security_rpc_idempotency_status NOT NULL DEFAULT 'PROCESSING',
    error_code                                 varchar(80),

    processed_at                                 timestamptz,
    expires_at                                     timestamptz NOT NULL,

    PRIMARY KEY (operation_type, idempotency_key),

    -- system_contract.md "Mandatory Invariants" #11: error_code is NULL
    -- unless the record is FAILED, and then it only ever holds a sanitized
    -- functional code, never a secret or stack trace.
    CONSTRAINT ck_security_rpc_idempotency_error_code_only_when_failed
        CHECK (error_code IS NULL OR status = 'FAILED')
);

CREATE INDEX ix_security_rpc_idempotency_account ON security_rpc_idempotency_records (account_id);
CREATE INDEX ix_security_rpc_idempotency_expires ON security_rpc_idempotency_records (expires_at);

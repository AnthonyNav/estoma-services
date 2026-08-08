-- idempotency_records — functional idempotency for durable commands.
--
-- Not defined by estomatologia-platform-docs (data-model.md has no table
-- for it); messaging-architecture.md §10.1 and commands.md §9 mandate
-- that a repeated intention (same producer + idempotencyKey) must not
-- reapply its durable effect, but leave the storage mechanism to each
-- service. This is that mechanism (docs/system-contract.md records it as
-- a gap-filling decision, not a platform contract).
--
-- Distinct from processed_messages (Consumer Inbox, keyed by messageId,
-- protects delivery of one specific message): this table protects the
-- FUNCTIONAL INTENTION, keyed by (producer, idempotency_key), and
-- recognizes a retry that arrives under a brand-new messageId.
--
-- Stores only the terminal OUTCOME needed to reconstruct a Result, never
-- the Result itself: aggregate_version for SUCCEEDED, error_code for
-- REJECTED. request_fingerprint lets a retry be told apart from a
-- different intention that happens to reuse the same key.

CREATE TYPE idempotency_status AS ENUM ('IN_PROGRESS', 'SUCCEEDED', 'REJECTED');

CREATE TABLE idempotency_records (
    producer                 varchar(120) NOT NULL,
    idempotency_key           varchar(200) NOT NULL,

    message_type               varchar(80) NOT NULL,
    aggregate_id                 uuid NOT NULL,
    request_fingerprint            varchar(64) NOT NULL,

    claimed_by_message_id           uuid NOT NULL,

    terminal_status                    idempotency_status NOT NULL DEFAULT 'IN_PROGRESS',
    aggregate_version                    integer,
    error_code                             varchar(80),

    created_at                                timestamptz NOT NULL,
    completed_at                               timestamptz,

    PRIMARY KEY (producer, idempotency_key),

    -- Ties the terminal fields to terminal_status so a transition can
    -- never leave them mismatched (mirrors ck_outbox_claim_state in
    -- V5__outbox_messages.sql).
    CONSTRAINT ck_idempotency_terminal_fields CHECK (
        (terminal_status = 'IN_PROGRESS'
            AND aggregate_version IS NULL AND error_code IS NULL AND completed_at IS NULL)
        OR
        (terminal_status = 'SUCCEEDED'
            AND aggregate_version IS NOT NULL AND error_code IS NULL AND completed_at IS NOT NULL)
        OR
        (terminal_status = 'REJECTED'
            AND aggregate_version IS NULL AND error_code IS NOT NULL AND completed_at IS NOT NULL)
    )
);

-- Supports a future retention purge job, mirroring
-- ix_processed_messages_processed_at (V4__processed_messages.sql).
CREATE INDEX ix_idempotency_completed_at ON idempotency_records (completed_at);

-- V6__runtime_privileges.sql grants only the tables that existed at that
-- point; a table created afterwards needs its own grant.
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_records TO ${runtime_role};

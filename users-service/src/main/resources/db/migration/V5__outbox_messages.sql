-- outbox_messages — Transactional Outbox. Not a domain entity.
--
-- Source: estomatologia-platform-docs
--   03-services/shared/users-service/data-model.md §2, §4
--
-- Deltas from that document, declared in docs/system-contract.md §8 and
-- §10 (D7):
--   1. `metadata jsonb` — carries destination/operationId/actor, none of
--      which are derivable from `payload` alone at relay time.
--   2. `claim_token` / `lease_until` — fence Outbox relay workers against
--      each other. `available_at` now means ONLY "the next instant a
--      PENDING row may be reclaimed"; it is no longer overloaded as the
--      PROCESSING lease deadline. `lease_until` is that deadline instead,
--      and `claim_token` is the current claim's identity: every
--      transition out of PROCESSING must present the token it was
--      claimed with, or it affects zero rows (Round 1 fencing).
--   3. `last_error_code` / `last_error_at` — the sanitized technical
--      reason (see OutboxFailureCode) the last publish attempt failed,
--      cleared back to NULL the moment a row reaches PUBLISHED.
--   4. `ix_outbox_pending` only covers PENDING rows now (PROCESSING has
--      its own index below), and `ck_outbox_claim_state` ties claim
--      columns to status so a transition can never leave them mismatched.

CREATE TABLE outbox_messages (
    message_id          uuid PRIMARY KEY,

    message_type         varchar(160) NOT NULL,
    message_version       integer NOT NULL,

    message_kind          varchar(20) NOT NULL,

    aggregate_id           uuid,
    aggregate_version       integer,

    correlation_id          uuid NOT NULL,
    causation_id             uuid,

    payload                   jsonb NOT NULL,
    metadata                  jsonb,

    status                     outbox_status NOT NULL DEFAULT 'PENDING',
    attempts                    integer NOT NULL DEFAULT 0,

    claim_token                 uuid,
    lease_until                  timestamptz,

    last_error_code               varchar(80),
    last_error_at                  timestamptz,

    occurred_at                      timestamptz NOT NULL,
    available_at                      timestamptz NOT NULL,
    published_at                       timestamptz,

    CONSTRAINT ck_outbox_claim_state CHECK (
        (status = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL)
    )
);

-- Partial: PENDING rows the relay can reclaim. Excludes PROCESSING (that
-- has its own index below) and PUBLISHED (so this index does not grow
-- unbounded as history accumulates).
CREATE INDEX ix_outbox_pending ON outbox_messages (available_at, occurred_at)
    WHERE status = 'PENDING';

-- The sweeper's and the stuck_processing metric's index: PROCESSING rows
-- whose lease has expired (docs/system-contract.md §8, §11).
CREATE INDEX ix_outbox_processing_lease ON outbox_messages (lease_until)
    WHERE status = 'PROCESSING';

CREATE INDEX ix_outbox_aggregate ON outbox_messages (aggregate_id);

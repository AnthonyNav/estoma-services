-- outbox_messages — Transactional Outbox. Not a domain entity.
--
-- Source: estomatologia-platform-docs
--   03-services/shared/users-service/data-model.md §2, §4
--
-- Two deltas from that document, both declared in docs/system-contract.md
-- §8 and §10 (D7):
--   1. `metadata jsonb` — carries destination/operationId/actor, none of
--      which are derivable from `payload` alone at relay time.
--   2. `ix_outbox_pending` is a partial index instead of a plain one, so it
--      stays small as PUBLISHED rows accumulate.

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

    occurred_at                  timestamptz NOT NULL,
    available_at                  timestamptz NOT NULL,
    published_at                   timestamptz
);

-- Partial: excludes PUBLISHED rows so the relay's hot index does not grow
-- unbounded as history accumulates. `available_at` doubles as PENDING
-- backoff and as the PROCESSING lease deadline (docs/system-contract.md §8).
CREATE INDEX ix_outbox_pending ON outbox_messages (available_at, occurred_at)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX ix_outbox_aggregate ON outbox_messages (aggregate_id);

-- outbox_messages — Transactional Outbox. Not a domain entity.
--
-- Single relay process polling PENDING rows with FOR UPDATE SKIP LOCKED and
-- exponential backoff via available_at (system_contract.md "Outbox
-- Operational Policy"). No claim-token/lease fencing in this tramo.
--
-- Source: communications-service/docs/system_contract.md, platform docs
-- data-model.md §2, §7.

CREATE TABLE outbox_messages (
    message_id          uuid PRIMARY KEY,

    message_type          varchar(160) NOT NULL,
    message_version         integer NOT NULL,
    message_kind              varchar(20) NOT NULL,

    aggregate_id                uuid,
    aggregate_version              integer,

    correlation_id                   uuid NOT NULL,
    causation_id                       uuid,

    payload                              jsonb NOT NULL,

    status                                 outbox_status NOT NULL DEFAULT 'PENDING',
    attempts                                 integer NOT NULL DEFAULT 0,

    occurred_at                                timestamptz NOT NULL,
    available_at                                 timestamptz NOT NULL,
    published_at                                   timestamptz,
    last_error                                       varchar(1000),

    CONSTRAINT ck_communications_outbox_attempts_non_negative
        CHECK (attempts >= 0)
);

CREATE INDEX ix_communications_outbox_pending ON outbox_messages (status, available_at);
CREATE INDEX ix_communications_outbox_aggregate ON outbox_messages (aggregate_id);

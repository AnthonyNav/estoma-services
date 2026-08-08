-- outbox_messages — Transactional Outbox. Not a domain entity.
--
-- Simple v1 schema (system_contract.md "Outbox Operational Policy"): a
-- single relay process polling PENDING rows with FOR UPDATE SKIP LOCKED
-- and exponential backoff via available_at. No claim-token/lease fencing
-- in this tramo — that level of sophistication (matching users-service's
-- outbox) is deferred to a later maturity pass, not part of this schema.
--
-- Source: authentication-service/docs/system_contract.md, platform docs
-- data-model.md §2, §4.

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

    CONSTRAINT ck_outbox_attempts_non_negative
        CHECK (attempts >= 0)
);

CREATE INDEX ix_auth_outbox_pending ON outbox_messages (status, available_at);
CREATE INDEX ix_auth_outbox_aggregate ON outbox_messages (aggregate_id);

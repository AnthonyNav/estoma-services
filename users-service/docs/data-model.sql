-- users-service — reference DDL for `users-db`
--
-- NON-AUTHORITATIVE. This file exists so the schema can be read in one place
-- without reconstructing it from Flyway migrations. The authoritative source
-- is src/main/resources/db/migration/V1__enums.sql .. V8__person_status_history_reason.sql.
-- DataModelReferenceTest asserts this file declares the same named objects
-- (tables, types, indexes, constraints, triggers) as those migrations (V6 is
-- excluded: it only grants runtime privileges and carries no shape).
--
-- Source of truth for the shape: estomatologia-platform-docs
--   03-services/shared/users-service/data-model.md
-- Deltas from that document are called out inline below.

-- ---------------------------------------------------------------------------
-- Enums
-- ---------------------------------------------------------------------------

CREATE TYPE person_status AS ENUM ('ACTIVE', 'INACTIVE');

CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED');

-- ---------------------------------------------------------------------------
-- persons — the official source of personal identity data.
-- ---------------------------------------------------------------------------

CREATE TABLE persons (
    person_id                      uuid PRIMARY KEY,

    first_name                     varchar(120) NOT NULL,
    paternal_surname               varchar(120) NOT NULL,
    maternal_surname               varchar(120),

    institutional_email            varchar(320) NOT NULL,
    institutional_email_normalized varchar(320) NOT NULL,

    phone                          varchar(30),

    status                         person_status NOT NULL DEFAULT 'ACTIVE',
    version                        integer NOT NULL DEFAULT 1,

    created_at                     timestamptz NOT NULL,
    updated_at                     timestamptz NOT NULL,
    deactivated_at                 timestamptz,

    CONSTRAINT ck_persons_first_name_not_blank
        CHECK (length(btrim(first_name)) > 0),
    CONSTRAINT ck_persons_paternal_not_blank
        CHECK (length(btrim(paternal_surname)) > 0),
    CONSTRAINT ck_persons_maternal_not_blank
        CHECK (maternal_surname IS NULL OR length(btrim(maternal_surname)) > 0),
    CONSTRAINT ck_persons_version_positive
        CHECK (version > 0),
    CONSTRAINT ck_persons_email_normalized
        CHECK (institutional_email_normalized = lower(btrim(institutional_email))),
    -- Data-model.md rules 5 and 6 expressed as one biconditional:
    -- an inactive person MUST have deactivated_at; an active person MUST NOT.
    CONSTRAINT ck_persons_deactivation_state
        CHECK (
            (status = 'INACTIVE' AND deactivated_at IS NOT NULL)
            OR (status = 'ACTIVE' AND deactivated_at IS NULL)
        )
);

CREATE INDEX ix_persons_email_normalized ON persons (institutional_email_normalized);
CREATE INDEX ix_persons_status ON persons (status);

-- DELTA from data-model.md: the document leaves the duplicate-detection rule
-- explicitly pending (see data-model.md §6) while already reserving
-- DUPLICATE_PERSON as a contractual rejection code. This unique index is the
-- interim rule this service ships with: one institutional mailbox belongs to
-- exactly one Person, active or inactive. See docs/system-contract.md §10
-- ("Interim decisions register") for the full justification and revisit
-- trigger.
CREATE UNIQUE INDEX ux_persons_email_normalized ON persons (institutional_email_normalized);

-- person_id is immutable (data-model.md §8 rule 1). Enforced with a trigger
-- rather than left as an application-only convention, because it protects
-- every future writer, including manual maintenance.
CREATE FUNCTION prevent_person_id_change() RETURNS trigger AS $$
BEGIN
    IF NEW.person_id <> OLD.person_id THEN
        RAISE EXCEPTION 'person_id is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_persons_id_immutable
    BEFORE UPDATE ON persons
    FOR EACH ROW
    EXECUTE FUNCTION prevent_person_id_change();

-- ---------------------------------------------------------------------------
-- person_status_history — append-only log of activations/deactivations.
-- ---------------------------------------------------------------------------

CREATE TABLE person_status_history (
    status_change_id      uuid PRIMARY KEY,
    person_id              uuid NOT NULL REFERENCES persons (person_id),

    previous_status         person_status,
    new_status              person_status NOT NULL,

    -- DELTA, V8: reasonCode/reasonDetail (commands.md §7.1), only meaningful
    -- for a deactivation (new_status = INACTIVE). varchar, not a Postgres
    -- enum -- data-model.md §5 fixes these exact column types and leaves the
    -- catalog-membership CHECK to the migration.
    reason_code              varchar(40),
    reason_detail            varchar(500),

    -- Logical reference to an authentication-service Account. No physical FK:
    -- data-model.md §7 forbids one, since accounts live in another database.
    changed_by_account_id   uuid,
    correlation_id           uuid,

    changed_at               timestamptz NOT NULL,

    -- DELTA, V8, all three added NOT VALID (existing rows grandfathered in,
    -- not backfilled with an invented reason):
    CONSTRAINT ck_person_status_history_reason_required_for_inactive
        CHECK (new_status <> 'INACTIVE' OR reason_code IS NOT NULL),
    CONSTRAINT ck_person_status_history_reason_detail_required_for_other
        CHECK (reason_code <> 'OTHER' OR (reason_detail IS NOT NULL AND btrim(reason_detail) <> '')),
    CONSTRAINT ck_person_status_history_reason_code_catalog
        CHECK (
            reason_code IS NULL
            OR reason_code IN ('DUPLICATE', 'CREATED_BY_ERROR', 'IDENTITY_CORRECTION', 'ADMINISTRATIVE_REQUEST', 'OTHER')
        )
);

CREATE INDEX ix_person_status_history ON person_status_history (person_id, changed_at);

-- ---------------------------------------------------------------------------
-- processed_messages — Consumer Inbox. Not a domain entity.
--
-- Registers every durable command consumed from commands.users-service, to
-- apply idempotency. Key is (consumer_name, message_id): the same messageId
-- could in principle be relevant to more than one logical consumer inside
-- this service in the future, so the pair — not messageId alone — is what a
-- consumer name owns.
-- ---------------------------------------------------------------------------

CREATE TABLE processed_messages (
    consumer_name    varchar(120) NOT NULL,
    message_id        uuid NOT NULL,
    correlation_id     uuid,
    processed_at        timestamptz NOT NULL,

    PRIMARY KEY (consumer_name, message_id)
);

CREATE INDEX ix_processed_messages_processed_at ON processed_messages (processed_at);

-- ---------------------------------------------------------------------------
-- outbox_messages — Transactional Outbox. Not a domain entity.
-- ---------------------------------------------------------------------------

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

    -- DELTA from data-model.md: an additive, nullable column. operationId and
    -- the actor are not derivable at relay time (the relay only reads
    -- outbox_messages) and have no home in the documented columns, yet the
    -- envelope requires them on RESULT messages and the actor is useful
    -- context on EVENT messages too. `payload` stays the pure contract
    -- payload; `metadata` carries transport/routing concerns:
    --   { "destination": "bff", "operationId": "uuid",
    --     "actor": {"type": "ACCOUNT", "accountId": "uuid"} }
    -- (or {"type": "SYSTEM", "service": "academic"} for a technical actor)
    -- Proposed upstream for the other four shared services in the companion
    -- ADR (see system-contract.md §10).
    metadata                  jsonb,

    status                     outbox_status NOT NULL DEFAULT 'PENDING',
    attempts                    integer NOT NULL DEFAULT 0,

    -- DELTA from data-model.md, Round 1 (durable command processing
    -- hardening, system-contract.md §8): fences Outbox relay workers
    -- against each other. `available_at` now means ONLY "the next instant
    -- a PENDING row may be reclaimed" -- it is no longer overloaded as the
    -- PROCESSING lease deadline. `lease_until` is that deadline instead;
    -- `claim_token` is the current claim's identity. Every transition out
    -- of PROCESSING must present the token it was claimed with, or it
    -- affects zero rows.
    claim_token                 uuid,
    lease_until                  timestamptz,

    -- DELTA, Round 1: the sanitized technical reason (OutboxFailureCode)
    -- the last publish attempt failed, cleared back to NULL the moment a
    -- row reaches PUBLISHED. Never a payload, a stack trace, or a raw
    -- exception message.
    last_error_code               varchar(80),
    last_error_at                  timestamptz,

    occurred_at                      timestamptz NOT NULL,
    available_at                      timestamptz NOT NULL,
    published_at                       timestamptz,

    -- DELTA, Round 1: ties claim columns to status so a transition can
    -- never leave them mismatched.
    CONSTRAINT ck_outbox_claim_state CHECK (
        (status = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL)
    )
);

-- DELTA from data-model.md: the document specifies a plain (status,
-- available_at) index. This is a partial-index refinement of the same
-- idea, now covering only PENDING rows (PROCESSING has its own index
-- below) so the relay's hot index does not grow unbounded as history
-- accumulates.
CREATE INDEX ix_outbox_pending ON outbox_messages (available_at, occurred_at)
    WHERE status = 'PENDING';

-- DELTA, Round 1: the sweeper's and the stuck_processing metric's index --
-- PROCESSING rows whose lease has expired (system-contract.md §8, §11).
CREATE INDEX ix_outbox_processing_lease ON outbox_messages (lease_until)
    WHERE status = 'PROCESSING';

CREATE INDEX ix_outbox_aggregate ON outbox_messages (aggregate_id);

-- ---------------------------------------------------------------------------
-- idempotency_records — functional idempotency for durable commands.
--
-- Not defined by data-model.md (no table there for it); messaging-
-- architecture.md §10.1 and commands.md §9 mandate that a repeated
-- intention (same producer + idempotencyKey) must not reapply its
-- durable effect, but leave the storage mechanism to each service. This
-- is that mechanism -- a gap-filling decision (system-contract.md §1),
-- not a platform contract.
--
-- Distinct from processed_messages (Consumer Inbox, keyed by messageId,
-- protects delivery of one specific message): this table protects the
-- FUNCTIONAL INTENTION, keyed by (producer, idempotency_key), and
-- recognizes a retry that arrives under a brand-new messageId. Stores
-- only the terminal OUTCOME needed to reconstruct a Result, never the
-- Result itself.
-- ---------------------------------------------------------------------------

CREATE TYPE idempotency_status AS ENUM ('IN_PROGRESS', 'SUCCEEDED', 'REJECTED');

CREATE TABLE idempotency_records (
    producer                 varchar(120) NOT NULL,
    idempotency_key           varchar(200) NOT NULL,

    message_type               varchar(80) NOT NULL,
    aggregate_id                 uuid NOT NULL,
    -- request_fingerprint tells a retry (same intention) apart from a
    -- different intention that happens to reuse the same key -- a SHA-256
    -- hex digest over the command's own functional fields, in a fixed
    -- order, excluding delivery/tracing metadata (messageId, operationId,
    -- correlationId, causationId, occurredAt).
    request_fingerprint         varchar(64) NOT NULL,

    claimed_by_message_id        uuid NOT NULL,

    terminal_status                idempotency_status NOT NULL DEFAULT 'IN_PROGRESS',
    aggregate_version                integer,
    error_code                         varchar(80),

    created_at                            timestamptz NOT NULL,
    completed_at                           timestamptz,

    PRIMARY KEY (producer, idempotency_key),

    -- Ties the terminal fields to terminal_status, mirroring
    -- ck_outbox_claim_state.
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
-- ix_processed_messages_processed_at.
CREATE INDEX ix_idempotency_completed_at ON idempotency_records (completed_at);

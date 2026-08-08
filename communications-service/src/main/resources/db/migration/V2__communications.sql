-- communications — the aggregate root this service owns: one request to
-- deliver a communication via inbox and/or email, identified by its
-- origin, recipient, system, and type.
--
-- Source: communications-service/docs/system_contract.md ("Mandatory
-- Invariants"), platform docs data-model.md §3, §8, commands.md §6.

CREATE TABLE communications (
    communication_id            uuid PRIMARY KEY,

    recipient_account_id        uuid NOT NULL,
    system_code                 varchar(80) NOT NULL,
    communication_type          varchar(120) NOT NULL,
    priority                    communication_priority NOT NULL,

    source_service               varchar(120) NOT NULL,
    source_reference_type        varchar(80) NOT NULL,
    source_reference_id          varchar(160) NOT NULL,
    initiated_by_account_id      uuid,

    status                        communication_status NOT NULL DEFAULT 'PENDING',

    created_at                     timestamptz NOT NULL,
    completed_at                     timestamptz,
    failed_at                          timestamptz
);

-- Idempotency key (commands.md §6): a RequestCommunication with the same
-- origin, recipient, system, and type never creates a second communication.
CREATE UNIQUE INDEX uq_communication_origin_recipient_type ON communications (
    source_service, source_reference_type, source_reference_id, recipient_account_id, system_code, communication_type
);

CREATE INDEX ix_communications_recipient_system ON communications (recipient_account_id, system_code, created_at);
CREATE INDEX ix_communications_status ON communications (status);

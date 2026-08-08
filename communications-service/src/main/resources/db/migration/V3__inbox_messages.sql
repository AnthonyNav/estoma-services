-- inbox_messages — the internal-inbox message a communication creates, at
-- most one per communication.
--
-- Source: communications-service/docs/system_contract.md ("Mandatory
-- Invariants": "a communication generates at most one inbox message"),
-- platform docs data-model.md §3, §5, §8.

CREATE TABLE inbox_messages (
    inbox_message_id    uuid PRIMARY KEY,
    communication_id    uuid NOT NULL UNIQUE REFERENCES communications (communication_id),

    title                varchar(300) NOT NULL,
    body                    text NOT NULL,
    status                     inbox_message_status NOT NULL DEFAULT 'ACTIVE',

    created_at                   timestamptz NOT NULL,
    expires_at                      timestamptz
);

CREATE INDEX ix_inbox_messages_status_expires ON inbox_messages (status, expires_at);

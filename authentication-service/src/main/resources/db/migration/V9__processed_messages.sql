-- processed_messages — Consumer Inbox. Not a domain entity.
--
-- Registers every durable command/event consumed by this service to apply
-- idempotency. Key is (consumer_name, message_id): the pair, not
-- message_id alone, is what a logical consumer name owns.
--
-- Source: authentication-service/docs/system_contract.md ("Migration
-- Tooling" retention policy), platform docs data-model.md §2, §4.

CREATE TABLE processed_messages (
    consumer_name    varchar(120) NOT NULL,
    message_id         uuid NOT NULL,
    processed_at          timestamptz NOT NULL,

    PRIMARY KEY (consumer_name, message_id)
);

-- Supports the 30-day retention purge job (system_contract.md "Migration Tooling").
CREATE INDEX ix_processed_messages_processed_at ON processed_messages (processed_at);

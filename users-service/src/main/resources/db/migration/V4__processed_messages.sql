-- processed_messages — Consumer Inbox. Not a domain entity.
--
-- Registers every durable command consumed from commands.users-service to
-- apply idempotency. Key is (consumer_name, message_id): the pair, not
-- message_id alone, is what a logical consumer name owns.
--
-- Source: estomatologia-platform-docs
--   03-services/shared/users-service/data-model.md §2, §4

CREATE TABLE processed_messages (
    consumer_name    varchar(120) NOT NULL,
    message_id        uuid NOT NULL,
    correlation_id     uuid,
    processed_at        timestamptz NOT NULL,

    PRIMARY KEY (consumer_name, message_id)
);

-- Supports the retention purge job (docs/system-contract.md §10, D8).
CREATE INDEX ix_processed_messages_processed_at ON processed_messages (processed_at);

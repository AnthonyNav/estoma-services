ALTER TABLE outbox_messages
    ADD COLUMN operation_id uuid,
    ADD COLUMN idempotency_key varchar(300),
    ADD COLUMN actor jsonb,
    ADD COLUMN destination varchar(120);

CREATE INDEX ix_communications_outbox_destination
    ON outbox_messages (destination)
    WHERE destination IS NOT NULL;

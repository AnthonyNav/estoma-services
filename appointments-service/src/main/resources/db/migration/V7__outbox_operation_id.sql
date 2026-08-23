ALTER TABLE outbox_messages
    ADD COLUMN operation_id uuid;

CREATE INDEX ix_outbox_operation_id
    ON outbox_messages (operation_id)
    WHERE operation_id IS NOT NULL;

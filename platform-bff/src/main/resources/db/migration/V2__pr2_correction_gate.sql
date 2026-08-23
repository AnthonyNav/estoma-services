CREATE TABLE quarantined_messages (
    consumer_name VARCHAR(160) NOT NULL,
    message_id UUID,
    reason_code VARCHAR(160) NOT NULL,
    body_sha256 VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, body_sha256)
);

CREATE UNIQUE INDEX idempotency_records_operation_uk
    ON idempotency_records (operation_id);

CREATE UNIQUE INDEX outbox_messages_operation_uk
    ON outbox_messages (operation_id);

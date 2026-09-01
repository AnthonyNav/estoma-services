-- Recoverable owner state needed by the V1 session context and effective-system composition.
CREATE TABLE account_presentation_read_model (
    account_id UUID PRIMARY KEY,
    person_id UUID NOT NULL,
    login_type VARCHAR(32) NOT NULL,
    login_identifier VARCHAR(320) NOT NULL,
    account_status VARCHAR(32) NOT NULL,
    source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE authorization_system_catalog_read_model (
    system_id UUID PRIMARY KEY,
    system_code VARCHAR(160) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    source_version BIGINT NOT NULL,
    trusted BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX mailbox_visible_system_page_idx
    ON mailbox_messages_read_model(recipient_account_id, system_code, created_at DESC, inbox_message_id ASC)
    WHERE communication_id IS NOT NULL AND expired_at IS NULL;

INSERT INTO projection_status(projection_name, status, generation, updated_at)
VALUES
  ('account-presentation', 'BOOTSTRAPPING', 0, CURRENT_TIMESTAMP),
  ('system-catalog', 'BOOTSTRAPPING', 0, CURRENT_TIMESTAMP)
ON CONFLICT (projection_name) DO NOTHING;

-- Correct the initial PR4 mailbox model without rewriting V3. Communications does not
-- publish owner stream versions for InboxVisited or InboxMessageExpired, and the version
-- attached to InboxMessageCreated belongs to Communication rather than InboxMessage.

ALTER TABLE mailbox_messages_read_model
  ALTER COLUMN communication_id DROP NOT NULL,
  ALTER COLUMN recipient_account_id DROP NOT NULL,
  ALTER COLUMN system_code DROP NOT NULL,
  ALTER COLUMN communication_type DROP NOT NULL,
  ALTER COLUMN title DROP NOT NULL,
  ALTER COLUMN body DROP NOT NULL,
  ALTER COLUMN created_at DROP NOT NULL,
  ADD COLUMN expired_at TIMESTAMPTZ,
  ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE mailbox_messages_read_model
SET updated_at = COALESCE(created_at, CURRENT_TIMESTAMP)
WHERE updated_at IS NULL;

ALTER TABLE mailbox_messages_read_model
  ALTER COLUMN updated_at SET NOT NULL,
  DROP COLUMN source_version,
  ADD CONSTRAINT mailbox_message_shape_ck CHECK (
    (
      communication_id IS NOT NULL
      AND recipient_account_id IS NOT NULL
      AND system_code IS NOT NULL
      AND communication_type IS NOT NULL
      AND title IS NOT NULL
      AND body IS NOT NULL
      AND created_at IS NOT NULL
    )
    OR
    (
      communication_id IS NULL
      AND recipient_account_id IS NULL
      AND system_code IS NULL
      AND communication_type IS NULL
      AND title IS NULL
      AND body IS NULL
      AND created_at IS NULL
      AND expired_at IS NOT NULL
    )
  );

CREATE INDEX mailbox_messages_visibility_idx
  ON mailbox_messages_read_model(recipient_account_id, system_code, created_at)
  WHERE communication_id IS NOT NULL AND expired_at IS NULL;

ALTER TABLE mailbox_visits_read_model
  DROP COLUMN source_version;

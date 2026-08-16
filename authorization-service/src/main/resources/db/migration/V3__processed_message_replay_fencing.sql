ALTER TABLE processed_messages
  ADD COLUMN claim_token uuid,
  ADD COLUMN lease_until timestamptz;

ALTER TABLE processed_messages
  ADD CONSTRAINT ck_processed_message_claim CHECK (
    (claim_token IS NULL AND lease_until IS NULL)
    OR (status = 'DEFERRED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
  );

CREATE INDEX ix_processed_messages_deferred_lease
  ON processed_messages (consumer_name, status, lease_until)
  WHERE status = 'DEFERRED';

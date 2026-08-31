-- Prior versions dispatched the in-process inbox-expiration maintenance
-- command through the external-result path. These rows have no consumer and
-- must not keep readiness down or be replayed. Keep the records for audit;
-- do not treat them as delivered.
UPDATE outbox_messages
SET status = 'DISCARDED',
    last_error = 'DISCARDED_INTERNAL_RESULT_NO_CONSUMER'
WHERE status = 'FAILED'
  AND message_kind = 'RESULT'
  AND message_type = 'ExpireInboxMessagesSucceeded'
  AND (destination IS NULL OR destination = 'communications')
  AND (
    actor IS NULL
    OR actor = '{"type":"SYSTEM","service":"communications","accountId":null}'::jsonb
    OR actor = '{"type":"SYSTEM","service":"communications"}'::jsonb
  );

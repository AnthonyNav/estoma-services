-- A previous scheduler instance can emit a final result while its replacement
-- is starting. The result is still strictly internal and has no consumer, so
-- retain it for audit without keeping readiness down or attempting a replay.
UPDATE outbox_messages
SET status = 'DISCARDED',
    last_error = 'DISCARDED_INTERNAL_RESULT_NO_CONSUMER'
WHERE status IN ('PENDING', 'FAILED')
  AND message_kind = 'RESULT'
  AND message_type = 'ExpireInboxMessagesSucceeded'
  AND (destination IS NULL OR destination = 'communications')
  AND (
    actor IS NULL
    OR actor = '{"type":"SYSTEM","service":"communications","accountId":null}'::jsonb
    OR actor = '{"type":"SYSTEM","service":"communications"}'::jsonb
  );

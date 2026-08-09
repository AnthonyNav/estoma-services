ALTER TABLE outbox_messages
    ADD COLUMN result_requester varchar(80);

UPDATE outbox_messages
SET result_requester = 'bff'
WHERE message_kind = 'RESULT'
  AND result_requester IS NULL;

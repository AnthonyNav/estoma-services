ALTER TABLE pending_wash_execution_events
    ADD COLUMN correlation_id uuid;

UPDATE pending_wash_execution_events
SET correlation_id = source_event_id
WHERE correlation_id IS NULL;

ALTER TABLE pending_wash_execution_events
    ALTER COLUMN correlation_id SET NOT NULL;

ALTER TABLE pending_capacity_evaluation
    ADD COLUMN previous_normal_capacity integer,
    ADD COLUMN current_normal_capacity integer,
    ADD COLUMN previous_journey_capacity integer,
    ADD COLUMN current_journey_capacity integer,
    ADD COLUMN previous_immunocompromised_capacity integer,
    ADD COLUMN current_immunocompromised_capacity integer,
    ADD COLUMN projection_version bigint;

-- No production code enqueued this causal path before V12; keep upgrade compatibility for any
-- historical technical rows while requiring the projection version for all new rows in the adapter.
CREATE INDEX ix_pending_capacity_execution_open
    ON pending_capacity_evaluation (wash_execution_id, status, available_at)
    WHERE status IN ('PENDING','PROCESSING');

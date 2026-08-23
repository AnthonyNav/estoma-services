CREATE TABLE projection_recovery_jobs (
    projection_name varchar(160) PRIMARY KEY,
    generation_id uuid NOT NULL,
    phase varchar(80) NOT NULL,
    current_query_message_id uuid NOT NULL,
    cursor varchar(300),
    context_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_projection_recovery_query UNIQUE (current_query_message_id)
);

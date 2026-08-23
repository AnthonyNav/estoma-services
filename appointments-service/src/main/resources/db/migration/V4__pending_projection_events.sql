CREATE TABLE pending_projection_events (
    pending_projection_event_id uuid PRIMARY KEY,
    source_event_id uuid NOT NULL UNIQUE,
    projection_name varchar(80) NOT NULL,
    message_type varchar(160) NOT NULL,
    aggregate_version bigint,
    envelope_json jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL,
    claimed_by varchar(200),
    lease_until timestamptz,
    completed_at timestamptz,
    last_error varchar(1000),
    CONSTRAINT ck_pending_projection_status CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT ck_pending_projection_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_pending_projection_claim
    ON pending_projection_events (projection_name, status, available_at);

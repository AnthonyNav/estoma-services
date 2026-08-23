CREATE TABLE projection_recovery_jobs (
    recovery_job_id uuid PRIMARY KEY,
    projection_name varchar(120) NOT NULL,
    scope varchar(16) NOT NULL,
    entity_key varchar(240),
    service_date date,
    status varchar(32) NOT NULL,
    cursor varchar(1000),
    query_attempt integer NOT NULL DEFAULT 0,
    current_query_type varchar(160),
    current_query_message_id uuid,
    current_operation_id uuid,
    current_correlation_id uuid,
    last_error_code varchar(200),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    claim_token uuid,
    lease_until timestamptz,
    CONSTRAINT ck_recovery_job_scope CHECK (scope IN ('FULL','ENTITY')),
    CONSTRAINT ck_recovery_job_status CHECK (status IN ('PENDING','WAITING_RESULT','COMPLETED','FAILED')),
    CONSTRAINT ck_recovery_job_entity_key CHECK (
      (scope='ENTITY' AND entity_key IS NOT NULL) OR (scope='FULL' AND entity_key IS NULL)),
    CONSTRAINT ck_recovery_job_waiting CHECK (
      (status='WAITING_RESULT' AND current_query_type IS NOT NULL AND current_query_message_id IS NOT NULL
        AND current_operation_id IS NOT NULL AND current_correlation_id IS NOT NULL AND claim_token IS NULL AND lease_until IS NULL)
      OR (status<>'WAITING_RESULT' AND current_query_type IS NULL AND current_query_message_id IS NULL
        AND current_operation_id IS NULL AND current_correlation_id IS NULL)),
    CONSTRAINT ck_recovery_job_claim CHECK (
      (claim_token IS NULL AND lease_until IS NULL)
      OR (status='PENDING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE UNIQUE INDEX ux_recovery_full_active ON projection_recovery_jobs(projection_name, COALESCE(service_date, DATE '0001-01-01'))
  WHERE scope='FULL' AND status IN ('PENDING','WAITING_RESULT');
CREATE UNIQUE INDEX ux_recovery_entity_active ON projection_recovery_jobs(projection_name, entity_key)
  WHERE scope='ENTITY' AND status IN ('PENDING','WAITING_RESULT');
CREATE INDEX ix_recovery_pending ON projection_recovery_jobs(status, updated_at);
CREATE INDEX ix_recovery_current_query ON projection_recovery_jobs(current_query_message_id)
  WHERE status='WAITING_RESULT';

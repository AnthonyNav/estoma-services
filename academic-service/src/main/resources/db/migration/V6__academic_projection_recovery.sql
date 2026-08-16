ALTER TABLE system_access_context_projection
  ADD COLUMN system_id uuid,
  ADD COLUMN system_access_id uuid;

ALTER TABLE processed_messages
  ADD COLUMN causation_id uuid,
  ADD COLUMN operation_id uuid,
  ADD COLUMN idempotency_key varchar(200),
  ADD COLUMN received_at timestamptz,
  ADD COLUMN applied_at timestamptz,
  ADD COLUMN deferred_reason varchar(80);

CREATE TYPE projection_recovery_scope AS ENUM ('FULL', 'ENTITY');
CREATE TYPE projection_recovery_status AS ENUM ('PENDING', 'WAITING_RESULT', 'COMPLETED', 'FAILED');
CREATE TYPE academic_projection_recovery_status AS ENUM ('READY', 'REBUILDING', 'FAILED');

CREATE TABLE projection_recovery_jobs (
  recovery_job_id uuid PRIMARY KEY,
  projection_type varchar(80) NOT NULL CHECK (projection_type = 'AUTHORIZATION'),
  scope projection_recovery_scope NOT NULL,
  entity_key varchar(320),
  status projection_recovery_status NOT NULL,
  cursor varchar(500),
  query_attempt integer NOT NULL DEFAULT 0 CHECK (query_attempt >= 0),
  current_query_type varchar(160),
  current_query_message_id uuid,
  current_operation_id uuid,
  current_correlation_id uuid,
  current_system_code varchar(80),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  last_error_code varchar(80),
  last_error_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  completed_at timestamptz,
  claim_token uuid,
  lease_until timestamptz,
  CHECK ((scope = 'FULL' AND entity_key IS NULL) OR (scope = 'ENTITY' AND entity_key IS NOT NULL)),
  CHECK (
    (status = 'WAITING_RESULT'
      AND current_query_type IS NOT NULL
      AND current_query_message_id IS NOT NULL
      AND current_operation_id IS NOT NULL
      AND current_correlation_id IS NOT NULL)
    OR status <> 'WAITING_RESULT'
  ),
  CHECK (
    (claim_token IS NULL AND lease_until IS NULL)
    OR (claim_token IS NOT NULL AND lease_until IS NOT NULL)
  ),
  CHECK (
    (status IN ('COMPLETED', 'FAILED') AND completed_at IS NOT NULL)
    OR status NOT IN ('COMPLETED', 'FAILED')
  )
);

CREATE UNIQUE INDEX uq_projection_recovery_full_active
  ON projection_recovery_jobs (projection_type)
  WHERE scope = 'FULL' AND status IN ('PENDING', 'WAITING_RESULT');

CREATE UNIQUE INDEX uq_projection_recovery_entity_active
  ON projection_recovery_jobs (projection_type, entity_key)
  WHERE scope = 'ENTITY' AND status IN ('PENDING', 'WAITING_RESULT');

CREATE INDEX ix_projection_recovery_pending
  ON projection_recovery_jobs (status, updated_at)
  WHERE status = 'PENDING';

CREATE INDEX ix_projection_recovery_waiting
  ON projection_recovery_jobs (current_correlation_id, current_operation_id)
  WHERE status = 'WAITING_RESULT';

CREATE TABLE projection_recovery_not_found_conflicts (
  conflict_id uuid PRIMARY KEY,
  recovery_job_id uuid NOT NULL REFERENCES projection_recovery_jobs ON DELETE CASCADE,
  account_id uuid NOT NULL,
  component varchar(40) NOT NULL,
  result_message_id uuid NOT NULL,
  created_at timestamptz NOT NULL
);

CREATE TABLE authorization_projection_degraded_accounts (
  account_id uuid PRIMARY KEY,
  reason varchar(80) NOT NULL,
  updated_at timestamptz NOT NULL
);

CREATE TABLE academic_projection_recovery_state (
  projection_type varchar(80) PRIMARY KEY CHECK (projection_type = 'AUTHORIZATION'),
  status academic_projection_recovery_status NOT NULL,
  active_job_id uuid REFERENCES projection_recovery_jobs,
  updated_at timestamptz NOT NULL,
  recovery_started_at timestamptz,
  snapshot_generated_at timestamptz
);

INSERT INTO academic_projection_recovery_state (projection_type, status, updated_at)
VALUES ('AUTHORIZATION', 'READY', now())
ON CONFLICT (projection_type) DO NOTHING;

CREATE INDEX ix_eligibility_system_cursor
  ON student_academic_eligibilities (system_code, eligibility_id);

-- V2 granted existing tables before PR3 existed. Keep the runtime role able
-- to execute recovery transitions without granting schema or migration rights.
GRANT SELECT, INSERT, UPDATE ON projection_recovery_jobs TO ${runtime_role};
GRANT SELECT, INSERT ON projection_recovery_not_found_conflicts TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON authorization_projection_degraded_accounts TO ${runtime_role};
GRANT SELECT, UPDATE ON academic_projection_recovery_state TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON authorization_context_projection TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON system_access_context_projection TO ${runtime_role};

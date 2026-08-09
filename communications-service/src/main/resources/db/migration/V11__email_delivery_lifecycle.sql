-- Additive lifecycle hardening. V1-V10 are intentionally immutable.
ALTER TYPE email_delivery_status ADD VALUE IF NOT EXISTS 'WAITING_FOR_RECIPIENT';
ALTER TYPE email_delivery_status ADD VALUE IF NOT EXISTS 'INDETERMINATE';
ALTER TYPE email_attempt_status ADD VALUE IF NOT EXISTS 'READY_TO_SEND';
ALTER TYPE email_attempt_status ADD VALUE IF NOT EXISTS 'CALL_STARTED';
ALTER TYPE email_attempt_status ADD VALUE IF NOT EXISTS 'INDETERMINATE';

ALTER TABLE email_deliveries
  ADD COLUMN IF NOT EXISTS retry_cycle integer NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS cycle_attempt_count integer NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS claim_token uuid,
  ADD COLUMN IF NOT EXISTS claimed_at timestamptz,
  ADD COLUMN IF NOT EXISTS lease_until timestamptz,
  ADD COLUMN IF NOT EXISTS worker_id varchar(160),
  ADD COLUMN IF NOT EXISTS sanitized_error_detail varchar(1000),
  ADD CONSTRAINT ck_email_deliveries_retry_cycle_positive CHECK (retry_cycle >= 1),
  ADD CONSTRAINT ck_email_deliveries_cycle_attempt_count_non_negative CHECK (cycle_attempt_count >= 0);

ALTER TABLE email_delivery_attempts
  ADD COLUMN IF NOT EXISTS retry_cycle integer NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS cycle_attempt_number integer NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS claim_token uuid,
  ADD COLUMN IF NOT EXISTS call_started_at timestamptz,
  ADD COLUMN IF NOT EXISTS sanitized_error_detail varchar(1000),
  ALTER COLUMN completed_at DROP NOT NULL,
  DROP CONSTRAINT IF EXISTS uq_email_delivery_attempt;

ALTER TABLE email_delivery_attempts
  ADD CONSTRAINT uq_email_delivery_attempt UNIQUE (email_delivery_id, attempt_number),
  ADD CONSTRAINT uq_email_delivery_attempt_cycle UNIQUE (email_delivery_id, retry_cycle, cycle_attempt_number),
  ADD CONSTRAINT ck_email_delivery_attempt_number_positive CHECK (attempt_number >= 1),
  ADD CONSTRAINT ck_email_delivery_attempt_retry_cycle_positive CHECK (retry_cycle >= 1),
  ADD CONSTRAINT ck_email_delivery_attempt_cycle_number_valid CHECK (cycle_attempt_number BETWEEN 1 AND 6);
CREATE INDEX IF NOT EXISTS ix_email_deliveries_lease ON email_deliveries (status, lease_until);

ALTER TABLE communications
  ADD COLUMN IF NOT EXISTS requested_channels text[] NOT NULL DEFAULT ARRAY[]::text[],
  ADD COLUMN IF NOT EXISTS source_message_id uuid,
  ADD COLUMN IF NOT EXISTS idempotency_key varchar(300),
  ADD COLUMN IF NOT EXISTS request_fingerprint varchar(64),
  ADD COLUMN IF NOT EXISTS correlation_id uuid,
  ADD COLUMN IF NOT EXISTS causation_id uuid;
CREATE UNIQUE INDEX IF NOT EXISTS uq_communications_source_idempotency
  ON communications (source_service, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_communications_correlation ON communications (correlation_id);

CREATE TABLE IF NOT EXISTS email_delivery_administrative_actions (
    action_id uuid PRIMARY KEY,
    command_message_id uuid NOT NULL,
    email_delivery_id uuid NOT NULL REFERENCES email_deliveries(email_delivery_id),
    action varchar(80) NOT NULL,
    actor_service varchar(160) NOT NULL,
    reason varchar(1000) NOT NULL,
    context jsonb NOT NULL DEFAULT '{}'::jsonb,
    resulting_retry_cycle integer,
    duplicate_risk boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    UNIQUE (command_message_id, email_delivery_id, action)
);

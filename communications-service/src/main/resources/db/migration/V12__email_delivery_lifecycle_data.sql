-- Lifecycle columns, constraints, administrative audit, and legacy repair.
ALTER TABLE email_deliveries
  ADD COLUMN IF NOT EXISTS retry_cycle integer NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS cycle_attempt_count integer NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS claim_token uuid,
  ADD COLUMN IF NOT EXISTS claimed_at timestamptz,
  ADD COLUMN IF NOT EXISTS lease_until timestamptz,
  ADD COLUMN IF NOT EXISTS worker_id varchar(160),
  ADD COLUMN IF NOT EXISTS sanitized_error_detail varchar(1000);
UPDATE email_deliveries SET retry_cycle = 1, cycle_attempt_count = attempt_count WHERE retry_cycle IS NULL OR cycle_attempt_count IS NULL;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_email_deliveries_retry_cycle_positive') THEN
    ALTER TABLE email_deliveries ADD CONSTRAINT ck_email_deliveries_retry_cycle_positive CHECK (retry_cycle >= 1);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_email_deliveries_cycle_attempt_count_non_negative') THEN
    ALTER TABLE email_deliveries ADD CONSTRAINT ck_email_deliveries_cycle_attempt_count_non_negative CHECK (cycle_attempt_count >= 0);
  END IF;
END $$;

ALTER TABLE email_delivery_attempts
  ADD COLUMN IF NOT EXISTS retry_cycle integer NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS cycle_attempt_number integer NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS claim_token uuid,
  ADD COLUMN IF NOT EXISTS call_started_at timestamptz,
  ADD COLUMN IF NOT EXISTS sanitized_error_detail varchar(1000);
UPDATE email_delivery_attempts SET retry_cycle = 1, cycle_attempt_number = attempt_number
 WHERE retry_cycle IS NULL OR cycle_attempt_number IS NULL;
ALTER TABLE email_delivery_attempts ALTER COLUMN completed_at DROP NOT NULL;
ALTER TABLE email_delivery_attempts DROP CONSTRAINT IF EXISTS uq_email_delivery_attempt;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_email_delivery_attempt') THEN
    ALTER TABLE email_delivery_attempts ADD CONSTRAINT uq_email_delivery_attempt UNIQUE (email_delivery_id, attempt_number);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_email_delivery_attempt_cycle') THEN
    ALTER TABLE email_delivery_attempts ADD CONSTRAINT uq_email_delivery_attempt_cycle UNIQUE (email_delivery_id, retry_cycle, cycle_attempt_number);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_email_delivery_attempt_number_positive') THEN
    ALTER TABLE email_delivery_attempts ADD CONSTRAINT ck_email_delivery_attempt_number_positive CHECK (attempt_number >= 1);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_email_delivery_attempt_retry_cycle_positive') THEN
    ALTER TABLE email_delivery_attempts ADD CONSTRAINT ck_email_delivery_attempt_retry_cycle_positive CHECK (retry_cycle >= 1);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_email_delivery_attempt_cycle_number_valid') THEN
    ALTER TABLE email_delivery_attempts ADD CONSTRAINT ck_email_delivery_attempt_cycle_number_valid CHECK (cycle_attempt_number BETWEEN 1 AND 6);
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS ix_email_deliveries_lease ON email_deliveries (status, lease_until);

ALTER TABLE communications
  ADD COLUMN IF NOT EXISTS requested_channels text[] NOT NULL DEFAULT ARRAY[]::text[],
  ADD COLUMN IF NOT EXISTS source_message_id uuid,
  ADD COLUMN IF NOT EXISTS idempotency_key varchar(300),
  ADD COLUMN IF NOT EXISTS request_fingerprint varchar(64),
  ADD COLUMN IF NOT EXISTS correlation_id uuid,
  ADD COLUMN IF NOT EXISTS causation_id uuid;
UPDATE communications c SET requested_channels = ARRAY_REMOVE(ARRAY[
  CASE WHEN EXISTS (SELECT 1 FROM inbox_messages i WHERE i.communication_id = c.communication_id) THEN 'INBOX' END,
  CASE WHEN EXISTS (SELECT 1 FROM email_deliveries e WHERE e.communication_id = c.communication_id) THEN 'EMAIL' END], NULL)
 WHERE cardinality(c.requested_channels) = 0;
CREATE UNIQUE INDEX IF NOT EXISTS uq_communications_source_idempotency ON communications (source_service, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_communications_correlation ON communications (correlation_id);
UPDATE communications SET correlation_id = gen_random_uuid() WHERE correlation_id IS NULL;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_communications_idempotency_fingerprint_pair') THEN
    ALTER TABLE communications ADD CONSTRAINT ck_communications_idempotency_fingerprint_pair
      CHECK ((idempotency_key IS NULL) = (request_fingerprint IS NULL));
  END IF;
END $$;

UPDATE email_deliveries SET status = CAST('INDETERMINATE' AS email_delivery_status), last_error_code = 'LEGACY_SENDING_OUTCOME_UNKNOWN',
  last_error_message = 'legacy SENDING outcome is unknown', claim_token = NULL, claimed_at = NULL, lease_until = NULL, worker_id = NULL
 WHERE status = CAST('SENDING' AS email_delivery_status);
UPDATE email_delivery_attempts SET status = CAST('INDETERMINATE' AS email_attempt_status), normalized_error_code = 'LEGACY_SENDING_OUTCOME_UNKNOWN',
  error_message = 'legacy SENDING outcome is unknown', claim_token = NULL, completed_at = COALESCE(completed_at, now())
 WHERE status IN (CAST('CALL_STARTED' AS email_attempt_status), CAST('READY_TO_SEND' AS email_attempt_status));

CREATE TABLE IF NOT EXISTS email_delivery_administrative_actions (
  action_id uuid PRIMARY KEY, command_message_id uuid NOT NULL, email_delivery_id uuid NOT NULL REFERENCES email_deliveries(email_delivery_id),
  action varchar(80) NOT NULL, actor jsonb NOT NULL, reason varchar(1000) NOT NULL,
  context jsonb NOT NULL DEFAULT '{}'::jsonb, resulting_retry_cycle integer, duplicate_risk boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL, UNIQUE (command_message_id, email_delivery_id, action)
);
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'email_delivery_administrative_actions' AND column_name = 'actor_service')
     AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'email_delivery_administrative_actions' AND column_name = 'actor') THEN
    ALTER TABLE email_delivery_administrative_actions RENAME COLUMN actor_service TO actor;
    ALTER TABLE email_delivery_administrative_actions ALTER COLUMN actor TYPE jsonb USING jsonb_build_object('type', 'SYSTEM', 'service', actor);
  END IF;
END $$;

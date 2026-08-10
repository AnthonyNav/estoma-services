-- Lifecycle columns, constraints, administrative audit, and legacy repair.
ALTER TABLE email_deliveries
  ADD COLUMN IF NOT EXISTS retry_cycle integer,
  ADD COLUMN IF NOT EXISTS cycle_attempt_count integer,
  ADD COLUMN IF NOT EXISTS claim_token uuid,
  ADD COLUMN IF NOT EXISTS claimed_at timestamptz,
  ADD COLUMN IF NOT EXISTS lease_until timestamptz,
  ADD COLUMN IF NOT EXISTS worker_id varchar(160),
  ADD COLUMN IF NOT EXISTS sanitized_error_detail varchar(1000),
  ADD COLUMN IF NOT EXISTS sender_code varchar(120);

ALTER TABLE email_delivery_attempts
  ADD COLUMN IF NOT EXISTS retry_cycle integer,
  ADD COLUMN IF NOT EXISTS cycle_attempt_number integer,
  ADD COLUMN IF NOT EXISTS claim_token uuid,
  ADD COLUMN IF NOT EXISTS call_started_at timestamptz,
  ADD COLUMN IF NOT EXISTS sanitized_error_detail varchar(1000);
ALTER TABLE email_delivery_attempts ALTER COLUMN completed_at DROP NOT NULL;

-- Delivery counters can lag durable attempt evidence from the old non-atomic worker.
WITH attempt_history AS (
  SELECT email_delivery_id, MAX(attempt_number) AS max_attempt_number
    FROM email_delivery_attempts
   GROUP BY email_delivery_id
)
UPDATE email_deliveries d
   SET attempt_count = GREATEST(d.attempt_count, COALESCE(h.max_attempt_number, 0))
  FROM attempt_history h
 WHERE h.email_delivery_id = d.email_delivery_id;

UPDATE email_delivery_attempts
   SET retry_cycle = CASE WHEN attempt_number <= 5 THEN 1 ELSE attempt_number - 4 END,
       cycle_attempt_number = CASE WHEN attempt_number <= 5 THEN attempt_number ELSE 1 END
 WHERE retry_cycle IS NULL OR cycle_attempt_number IS NULL;

UPDATE email_deliveries
   SET retry_cycle = CASE
         WHEN status = CAST('PENDING' AS email_delivery_status) AND attempt_count > 0
           THEN CASE WHEN attempt_count <= 5 THEN 2 ELSE attempt_count - 3 END
         WHEN attempt_count <= 5 THEN 1
         ELSE attempt_count - 4
       END,
       cycle_attempt_count = CASE
         WHEN status = CAST('PENDING' AS email_delivery_status) THEN 0
         WHEN attempt_count = 0 THEN 0
         WHEN attempt_count <= 5 THEN attempt_count
         ELSE 1
       END
 WHERE retry_cycle IS NULL OR cycle_attempt_count IS NULL;

UPDATE email_deliveries SET status = CAST('INDETERMINATE' AS email_delivery_status), last_error_code = 'LEGACY_SENDING_OUTCOME_UNKNOWN',
  last_error_message = 'legacy SENDING outcome is unknown', claim_token = NULL, claimed_at = NULL, lease_until = NULL, worker_id = NULL
 WHERE status = CAST('SENDING' AS email_delivery_status);
UPDATE email_delivery_attempts SET status = CAST('INDETERMINATE' AS email_attempt_status), normalized_error_code = 'LEGACY_SENDING_OUTCOME_UNKNOWN',
  error_message = 'legacy SENDING outcome is unknown', claim_token = NULL, completed_at = COALESCE(completed_at, now())
 WHERE status = CAST('CALL_STARTED' AS email_attempt_status);

UPDATE email_deliveries SET accepted_at = COALESCE(accepted_at, delivered_at, updated_at, created_at)
 WHERE status IN (CAST('ACCEPTED' AS email_delivery_status), CAST('DELIVERED' AS email_delivery_status));
UPDATE email_deliveries SET delivered_at = COALESCE(delivered_at, accepted_at, updated_at, created_at)
 WHERE status = CAST('DELIVERED' AS email_delivery_status);
UPDATE email_deliveries SET failed_at = COALESCE(failed_at, updated_at, created_at)
 WHERE status = CAST('FAILED' AS email_delivery_status);
UPDATE email_deliveries SET accepted_at = NULL
 WHERE status NOT IN (CAST('ACCEPTED' AS email_delivery_status), CAST('DELIVERED' AS email_delivery_status));
UPDATE email_deliveries SET delivered_at = NULL
 WHERE status <> CAST('DELIVERED' AS email_delivery_status);
UPDATE email_deliveries SET failed_at = NULL
 WHERE status <> CAST('FAILED' AS email_delivery_status);

UPDATE email_deliveries SET claim_token = NULL, claimed_at = NULL, lease_until = NULL, worker_id = NULL
 WHERE status <> CAST('SENDING' AS email_delivery_status);
UPDATE email_delivery_attempts SET claim_token = NULL, call_started_at = NULL, completed_at = NULL
 WHERE status = CAST('READY_TO_SEND' AS email_attempt_status);
UPDATE email_delivery_attempts SET claim_token = NULL, completed_at = COALESCE(completed_at, started_at, now())
 WHERE status IN (CAST('ACCEPTED' AS email_attempt_status), CAST('FAILED' AS email_attempt_status), CAST('INDETERMINATE' AS email_attempt_status));

ALTER TABLE email_deliveries ALTER COLUMN retry_cycle SET DEFAULT 1;
ALTER TABLE email_deliveries ALTER COLUMN cycle_attempt_count SET DEFAULT 0;
ALTER TABLE email_deliveries ALTER COLUMN retry_cycle SET NOT NULL;
ALTER TABLE email_deliveries ALTER COLUMN cycle_attempt_count SET NOT NULL;
ALTER TABLE email_delivery_attempts ALTER COLUMN retry_cycle SET DEFAULT 1;
ALTER TABLE email_delivery_attempts ALTER COLUMN cycle_attempt_number SET DEFAULT 1;
ALTER TABLE email_delivery_attempts ALTER COLUMN retry_cycle SET NOT NULL;
ALTER TABLE email_delivery_attempts ALTER COLUMN cycle_attempt_number SET NOT NULL;
ALTER TABLE email_delivery_attempts DROP CONSTRAINT IF EXISTS uq_email_delivery_attempt;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_email_deliveries_retry_cycle_positive') THEN
    ALTER TABLE email_deliveries ADD CONSTRAINT ck_email_deliveries_retry_cycle_positive CHECK (retry_cycle >= 1);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_email_deliveries_cycle_attempt_count_non_negative') THEN
    ALTER TABLE email_deliveries ADD CONSTRAINT ck_email_deliveries_cycle_attempt_count_non_negative CHECK (cycle_attempt_count BETWEEN 0 AND 6);
  END IF;
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
  ADD COLUMN IF NOT EXISTS requested_channels text[],
  ADD COLUMN IF NOT EXISTS source_message_id uuid,
  ADD COLUMN IF NOT EXISTS idempotency_key varchar(300),
  ADD COLUMN IF NOT EXISTS request_fingerprint varchar(64),
  ADD COLUMN IF NOT EXISTS correlation_id uuid,
  ADD COLUMN IF NOT EXISTS causation_id uuid;
UPDATE communications c SET requested_channels = ARRAY_REMOVE(ARRAY[
  CASE WHEN EXISTS (SELECT 1 FROM inbox_messages i WHERE i.communication_id = c.communication_id) THEN 'INBOX' END,
  CASE WHEN EXISTS (SELECT 1 FROM email_deliveries e WHERE e.communication_id = c.communication_id) THEN 'EMAIL' END], NULL)
 WHERE c.requested_channels IS NULL OR cardinality(c.requested_channels) = 0;

-- Rebuild the aggregate snapshot from durable channel evidence without emitting historical events.
UPDATE communications c
   SET status = CAST('COMPLETED' AS communication_status),
       completed_at = CASE
         WHEN d.status = CAST('DELIVERED' AS email_delivery_status)
           THEN COALESCE(d.delivered_at, d.accepted_at, d.updated_at, d.created_at, c.created_at)
         ELSE COALESCE(d.accepted_at, d.updated_at, d.created_at, c.created_at)
       END,
       failed_at = NULL
  FROM email_deliveries d
 WHERE d.communication_id = c.communication_id
   AND d.status IN (CAST('ACCEPTED' AS email_delivery_status), CAST('DELIVERED' AS email_delivery_status));
UPDATE communications c
   SET status = CAST('FAILED' AS communication_status),
       completed_at = NULL,
       failed_at = COALESCE(d.failed_at, d.updated_at, d.created_at, c.created_at)
  FROM email_deliveries d
 WHERE d.communication_id = c.communication_id
   AND d.status = CAST('FAILED' AS email_delivery_status);
UPDATE communications c
   SET status = CAST('PROCESSING' AS communication_status), completed_at = NULL, failed_at = NULL
  FROM email_deliveries d
 WHERE d.communication_id = c.communication_id
   AND d.status IN (CAST('WAITING_FOR_RECIPIENT' AS email_delivery_status), CAST('PENDING' AS email_delivery_status),
       CAST('RETRY_SCHEDULED' AS email_delivery_status), CAST('SENDING' AS email_delivery_status), CAST('INDETERMINATE' AS email_delivery_status));
UPDATE communications c
   SET status = CAST('COMPLETED' AS communication_status),
       completed_at = COALESCE(i.created_at, c.created_at), failed_at = NULL
  FROM inbox_messages i
 WHERE i.communication_id = c.communication_id
   AND NOT EXISTS (SELECT 1 FROM email_deliveries d WHERE d.communication_id = c.communication_id);
-- Legacy records without persisted channel evidence remain non-terminal and require no invented outcome.
UPDATE communications c
   SET status = CAST('PROCESSING' AS communication_status), completed_at = NULL, failed_at = NULL
 WHERE NOT EXISTS (SELECT 1 FROM email_deliveries d WHERE d.communication_id = c.communication_id)
   AND NOT EXISTS (SELECT 1 FROM inbox_messages i WHERE i.communication_id = c.communication_id);

ALTER TABLE communications ALTER COLUMN requested_channels SET DEFAULT ARRAY[]::text[];
ALTER TABLE communications ALTER COLUMN requested_channels SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_communications_source_idempotency ON communications (source_service, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_communications_correlation ON communications (correlation_id);

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_communications_idempotency_fingerprint_pair') THEN
    ALTER TABLE communications ADD CONSTRAINT ck_communications_idempotency_fingerprint_pair
      CHECK ((idempotency_key IS NULL) = (request_fingerprint IS NULL));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_communications_status_timestamps') THEN
    ALTER TABLE communications ADD CONSTRAINT ck_communications_status_timestamps CHECK (
      (status = CAST('COMPLETED' AS communication_status) AND completed_at IS NOT NULL AND failed_at IS NULL)
      OR (status = CAST('FAILED' AS communication_status) AND completed_at IS NULL AND failed_at IS NOT NULL)
      OR (status IN (CAST('PENDING' AS communication_status), CAST('PROCESSING' AS communication_status))
        AND completed_at IS NULL AND failed_at IS NULL)
    );
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_email_deliveries_claim_lifecycle') THEN
    ALTER TABLE email_deliveries ADD CONSTRAINT ck_email_deliveries_claim_lifecycle CHECK (
      (status = CAST('SENDING' AS email_delivery_status)
        AND claim_token IS NOT NULL AND claimed_at IS NOT NULL AND lease_until IS NOT NULL)
      OR
      (status <> CAST('SENDING' AS email_delivery_status)
        AND claim_token IS NULL AND claimed_at IS NULL AND lease_until IS NULL AND worker_id IS NULL)
    );
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_email_delivery_attempts_lifecycle') THEN
    ALTER TABLE email_delivery_attempts ADD CONSTRAINT ck_email_delivery_attempts_lifecycle CHECK (
      (status = CAST('CALL_STARTED' AS email_attempt_status)
        AND claim_token IS NOT NULL AND call_started_at IS NOT NULL AND completed_at IS NULL)
      OR
      (status = CAST('READY_TO_SEND' AS email_attempt_status)
        AND call_started_at IS NULL AND completed_at IS NULL)
      OR
      (status IN (CAST('ACCEPTED' AS email_attempt_status), CAST('FAILED' AS email_attempt_status), CAST('INDETERMINATE' AS email_attempt_status))
        AND claim_token IS NULL AND completed_at IS NOT NULL)
    );
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_email_deliveries_status_timestamps') THEN
    ALTER TABLE email_deliveries ADD CONSTRAINT ck_email_deliveries_status_timestamps CHECK (
      (status = CAST('ACCEPTED' AS email_delivery_status) AND accepted_at IS NOT NULL AND delivered_at IS NULL AND failed_at IS NULL)
      OR (status = CAST('DELIVERED' AS email_delivery_status) AND accepted_at IS NOT NULL AND delivered_at IS NOT NULL AND failed_at IS NULL)
      OR (status = CAST('FAILED' AS email_delivery_status) AND accepted_at IS NULL AND delivered_at IS NULL AND failed_at IS NOT NULL)
      OR (status IN (CAST('WAITING_FOR_RECIPIENT' AS email_delivery_status), CAST('PENDING' AS email_delivery_status),
          CAST('SENDING' AS email_delivery_status), CAST('RETRY_SCHEDULED' AS email_delivery_status), CAST('INDETERMINATE' AS email_delivery_status))
          AND accepted_at IS NULL AND delivered_at IS NULL AND failed_at IS NULL)
    );
  END IF;
END $$;

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

-- V10 ran before this table existed; grant it explicitly to the runtime role.
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE email_delivery_administrative_actions TO ${runtime_role};

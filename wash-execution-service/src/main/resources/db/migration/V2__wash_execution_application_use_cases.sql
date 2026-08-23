ALTER TABLE appointment_time_slot_projection
  ADD COLUMN IF NOT EXISTS calendar_id uuid;

CREATE TABLE command_idempotency_records (
    producer varchar(120) NOT NULL,
    operation_type varchar(160) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'IN_PROGRESS',
    result_json text,
    error_code varchar(160),
    aggregate_id uuid,
    aggregate_version integer,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    PRIMARY KEY (producer, operation_type, idempotency_key),
    CONSTRAINT ck_command_idempotency_status
      CHECK (status IN ('IN_PROGRESS','SUCCEEDED','REJECTED')),
    CONSTRAINT ck_command_idempotency_fingerprint
      CHECK (length(request_fingerprint) = 64),
    CONSTRAINT ck_command_idempotency_completion
      CHECK (
        (status = 'IN_PROGRESS' AND completed_at IS NULL AND result_json IS NULL)
        OR (status IN ('SUCCEEDED','REJECTED') AND completed_at IS NOT NULL AND result_json IS NOT NULL)
      )
);

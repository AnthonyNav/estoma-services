CREATE TABLE missed_evaluation_confirmations (
    appointment_id uuid PRIMARY KEY REFERENCES appointments (appointment_id) ON DELETE CASCADE,
    expected_appointment_version bigint NOT NULL,
    current_query_message_id uuid NOT NULL UNIQUE,
    status varchar(20) NOT NULL,
    snapshot_at timestamptz,
    arrived_at timestamptz,
    source_result_message_id uuid,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_missed_eval_version CHECK (expected_appointment_version > 0),
    CONSTRAINT ck_missed_eval_status CHECK (status IN ('QUERY_PENDING','CONFIRMED')),
    CONSTRAINT ck_missed_eval_confirmed CHECK (
      (status='QUERY_PENDING' AND snapshot_at IS NULL AND source_result_message_id IS NULL)
      OR (status='CONFIRMED' AND snapshot_at IS NOT NULL AND source_result_message_id IS NOT NULL)
    )
);

CREATE INDEX ix_missed_evaluation_status
    ON missed_evaluation_confirmations (status, updated_at);

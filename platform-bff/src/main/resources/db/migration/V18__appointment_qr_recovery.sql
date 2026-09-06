CREATE SEQUENCE appointment_qr_recovery_generation_seq START WITH 1;

CREATE TABLE appointment_qr_recovery_state (
    service_date DATE PRIMARY KEY,
    generation BIGINT NOT NULL UNIQUE,
    restart_revision BIGINT NOT NULL,
    recovery_job_id UUID NOT NULL UNIQUE REFERENCES projection_recovery_jobs(recovery_job_id),
    pass_number SMALLINT NOT NULL,
    page_number INTEGER NOT NULL,
    page_token VARCHAR(160),
    current_query_message_id UUID,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(160),
    CONSTRAINT appointment_qr_recovery_pass_ck CHECK (pass_number IN (1, 2)),
    CONSTRAINT appointment_qr_recovery_page_ck CHECK (page_number > 0),
    CONSTRAINT appointment_qr_recovery_restart_revision_ck CHECK (restart_revision >= 0),
    CONSTRAINT appointment_qr_recovery_status_ck CHECK (status IN ('RUNNING', 'READY', 'DEGRADED')),
    CONSTRAINT appointment_qr_recovery_current_query_ck CHECK (
      (status = 'RUNNING' AND current_query_message_id IS NOT NULL AND completed_at IS NULL)
      OR (status IN ('READY', 'DEGRADED') AND current_query_message_id IS NULL)
    )
);

CREATE TABLE appointment_qr_recovery_page_tokens (
    recovery_job_id UUID NOT NULL REFERENCES projection_recovery_jobs(recovery_job_id),
    pass_number SMALLINT NOT NULL,
    page_token VARCHAR(160) NOT NULL,
    PRIMARY KEY (recovery_job_id, pass_number, page_token),
    CONSTRAINT appointment_qr_recovery_token_pass_ck CHECK (pass_number IN (1, 2)),
    CONSTRAINT appointment_qr_recovery_token_length_ck CHECK (char_length(page_token) BETWEEN 1 AND 160)
);

-- Owner-derived appointment availability. A GET only reads a fresh snapshot; refresh is durable
-- and asynchronous so the BFF never performs a request-time RPC or reproduces matching rules.
CREATE TABLE appointment_availability_refresh (
    context_id UUID PRIMARY KEY,
    student_account_id UUID NOT NULL,
    service_date DATE NOT NULL,
    appointment_type VARCHAR(32) NOT NULL,
    instrument_count INTEGER NOT NULL,
    piece_type VARCHAR(32) NOT NULL,
    course_section_id UUID NOT NULL,
    query_message_id UUID NOT NULL UNIQUE,
    correlation_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    claim_token UUID,
    claim_until TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    result_message_id UUID,
    last_error_code VARCHAR(160),
    requested_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT appointment_availability_refresh_status_ck
      CHECK (status IN ('PENDING', 'PUBLISHED', 'APPLIED', 'REJECTED', 'FAILED')),
    CONSTRAINT appointment_availability_refresh_count_ck CHECK (instrument_count > 0)
);

CREATE INDEX appointment_availability_refresh_claim_idx
  ON appointment_availability_refresh(status, next_attempt_at, requested_at);

CREATE TABLE appointment_availability_read_model (
    context_id UUID PRIMARY KEY,
    student_account_id UUID NOT NULL,
    service_date DATE NOT NULL,
    appointment_type VARCHAR(32) NOT NULL,
    instrument_count INTEGER NOT NULL,
    piece_type VARCHAR(32) NOT NULL,
    course_section_id UUID NOT NULL,
    snapshot_id UUID NOT NULL UNIQUE,
    query_message_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    snapshot_generated_at TIMESTAMPTZ NOT NULL,
    fresh_until TIMESTAMPTZ NOT NULL,
    can_schedule BOOLEAN NOT NULL,
    blocking_reasons TEXT[] NOT NULL,
    daily_penalty_points INTEGER NOT NULL,
    daily_completed_appointments INTEGER NOT NULL,
    exceptional_authorization_required BOOLEAN NOT NULL,
    exceptional_authorization_available BOOLEAN NOT NULL,
    exceptional_authorization_id UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT appointment_availability_snapshot_count_ck
      CHECK (instrument_count > 0 AND daily_penalty_points >= 0 AND daily_completed_appointments >= 0),
    CONSTRAINT appointment_availability_snapshot_freshness_ck
      CHECK (fresh_until >= snapshot_generated_at),
    CONSTRAINT appointment_availability_snapshot_blockers_ck
      CHECK (can_schedule = (cardinality(blocking_reasons) = 0)),
    CONSTRAINT appointment_availability_snapshot_authorization_ck
      CHECK (exceptional_authorization_available = (exceptional_authorization_id IS NOT NULL)
        AND (NOT exceptional_authorization_available OR exceptional_authorization_required)
        AND (NOT exceptional_authorization_required OR exceptional_authorization_available
          OR blocking_reasons @> ARRAY['EXCEPTIONAL_AUTHORIZATION_REQUIRED']::TEXT[]))
);

CREATE TABLE appointment_availability_slot_read_model (
    context_id UUID NOT NULL REFERENCES appointment_availability_read_model(context_id) ON DELETE CASCADE,
    appointment_time_slot_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    available_capacity INTEGER NOT NULL,
    booking_deadline_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (context_id, appointment_time_slot_id),
    CONSTRAINT appointment_availability_slot_interval_ck
      CHECK (ends_at > starts_at AND booking_deadline_at <= starts_at),
    CONSTRAINT appointment_availability_slot_capacity_ck CHECK (available_capacity > 0)
);

CREATE INDEX appointment_availability_slot_order_idx
  ON appointment_availability_slot_read_model(context_id, starts_at, appointment_time_slot_id);

-- Owner-ranked reassignment candidates are refreshed asynchronously. HTTP GETs never mutate
-- these records and only serve a snapshot matching the current projected execution version.
CREATE TABLE reassignment_candidates_refresh (
    wash_execution_id UUID PRIMARY KEY,
    execution_version BIGINT NOT NULL,
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
    CONSTRAINT reassignment_candidates_refresh_status_ck
      CHECK (status IN ('PENDING', 'PUBLISHED', 'APPLIED', 'REJECTED', 'FAILED')),
    CONSTRAINT reassignment_candidates_refresh_version_ck CHECK (execution_version > 0)
);

CREATE INDEX reassignment_candidates_refresh_claim_idx
  ON reassignment_candidates_refresh(status, next_attempt_at, requested_at, wash_execution_id);

-- Retired Query identities remain bounded evidence for authentic late Results after a refresh
-- rollover. They prevent normal broker/confirm races from being misclassified as hostile traffic.
CREATE TABLE reassignment_candidates_refresh_history (
    query_message_id UUID PRIMARY KEY,
    wash_execution_id UUID NOT NULL,
    execution_version BIGINT NOT NULL,
    correlation_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL,
    published_at TIMESTAMPTZ,
    last_error_code VARCHAR(160),
    requested_at TIMESTAMPTZ NOT NULL,
    retired_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT reassignment_candidates_history_version_ck CHECK (execution_version > 0)
);

CREATE INDEX reassignment_candidates_refresh_history_retention_idx
  ON reassignment_candidates_refresh_history(retired_at, query_message_id);

CREATE TABLE reassignment_candidates_read_model (
    wash_execution_id UUID PRIMARY KEY,
    execution_version BIGINT NOT NULL,
    result_message_id UUID NOT NULL UNIQUE,
    query_message_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    snapshot_generated_at TIMESTAMPTZ NOT NULL,
    fresh_until TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT reassignment_candidates_snapshot_version_ck CHECK (execution_version > 0),
    CONSTRAINT reassignment_candidates_snapshot_freshness_ck
      CHECK (fresh_until = snapshot_generated_at + INTERVAL '10 seconds')
);

CREATE TABLE reassignment_candidate_read_model (
    wash_execution_id UUID NOT NULL
      REFERENCES reassignment_candidates_read_model(wash_execution_id) ON DELETE CASCADE,
    candidate_order INTEGER NOT NULL,
    cabin_id UUID NOT NULL,
    cabin_code VARCHAR(160) NOT NULL,
    cabin_name VARCHAR(320) NOT NULL,
    tank_id UUID NOT NULL,
    tank_code VARCHAR(160) NOT NULL,
    tank_name VARCHAR(320) NOT NULL,
    available_capacity INTEGER NOT NULL,
    PRIMARY KEY (wash_execution_id, candidate_order),
    CONSTRAINT reassignment_candidate_pair_uk UNIQUE (wash_execution_id, cabin_id, tank_id),
    CONSTRAINT reassignment_candidate_order_ck CHECK (candidate_order >= 0),
    CONSTRAINT reassignment_candidate_capacity_ck CHECK (available_capacity > 0),
    CONSTRAINT reassignment_candidate_text_ck CHECK (
      btrim(cabin_code) <> '' AND btrim(cabin_name) <> ''
      AND btrim(tank_code) <> '' AND btrim(tank_name) <> '')
);

CREATE TABLE administrative_audit_records (
    audit_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL,
    correlation_id uuid NOT NULL,
    operation_type varchar(120) NOT NULL,
    aggregate_id uuid NOT NULL,
    account_id uuid NOT NULL,
    reason varchar(500) NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT ck_administrative_audit_reason_not_blank CHECK (btrim(reason) <> '')
);

CREATE INDEX ix_administrative_audit_aggregate
    ON administrative_audit_records (aggregate_id, recorded_at);

CREATE INDEX ix_administrative_audit_operation
    ON administrative_audit_records (operation_id);

GRANT SELECT, INSERT ON administrative_audit_records TO ${runtime_role};

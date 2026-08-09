-- Finalize the V1 recovery lifecycle represented by V12's additive columns.
UPDATE password_recovery_requests
SET status = CASE
    WHEN status = 'ACTIVE' AND expires_at <= now() THEN 'EXPIRED'
    WHEN status NOT IN ('ACTIVE', 'INVALIDATED', 'USED', 'EXPIRED', 'ATTEMPTS_EXHAUSTED') THEN 'INVALIDATED'
    ELSE status
END;

-- V1 derives codes from recovery_id; token_hash is a derivation marker, not a
-- globally unique secret.
DROP INDEX uq_recovery_token;

ALTER TABLE password_recovery_requests
    ADD CONSTRAINT ck_recovery_status
        CHECK (status IN ('ACTIVE', 'INVALIDATED', 'USED', 'EXPIRED', 'ATTEMPTS_EXHAUSTED'));

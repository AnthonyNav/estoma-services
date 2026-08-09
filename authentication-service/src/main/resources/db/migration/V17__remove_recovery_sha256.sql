-- Replace the persisted recovery hash/legacy marker with an explicit derivation context.
UPDATE password_recovery_requests
SET code_derivation_context = 'HMAC_SHA256_V1',
    token_hash = NULL
WHERE token_hash = 'v1-hmac';

-- Active rows that cannot be verified under the canonical derivation must not remain usable.
UPDATE password_recovery_requests
SET status = 'INVALIDATED',
    invalidated_at = COALESCE(invalidated_at, now())
WHERE status = 'ACTIVE'
  AND code_derivation_context IS DISTINCT FROM 'HMAC_SHA256_V1';

-- Inactive legacy rows have no usability to preserve; canonicalize them so the new check succeeds.
UPDATE password_recovery_requests
SET code_derivation_context = 'HMAC_SHA256_V1',
    token_hash = NULL
WHERE code_derivation_context IS DISTINCT FROM 'HMAC_SHA256_V1'
   OR token_hash IS NOT NULL;

ALTER TABLE password_recovery_requests
    ALTER COLUMN code_derivation_context SET DEFAULT 'HMAC_SHA256_V1',
    ADD CONSTRAINT ck_recovery_code_derivation_context
        CHECK (code_derivation_context = 'HMAC_SHA256_V1'),
    ADD CONSTRAINT ck_recovery_token_hash_removed
        CHECK (token_hash IS NULL);

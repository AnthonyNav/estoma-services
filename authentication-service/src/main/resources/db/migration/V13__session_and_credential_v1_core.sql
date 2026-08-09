-- Move temporary-password state from the legacy account row to its credential
-- and retain V12's additive columns for existing installations.
UPDATE account_credentials credential
SET must_change_password = account.must_change_password,
    temporary_password_expires_at = account.temporary_password_expires_at
FROM accounts account
WHERE credential.account_id = account.account_id
  AND account.must_change_password = true
  AND credential.must_change_password = false;

ALTER TABLE accounts
    DROP COLUMN must_change_password,
    DROP COLUMN temporary_password_expires_at;

-- V12 populated the new expiry fields from expires_at. Reapply defensively for
-- databases where a previous migration ran before rows were inserted.
UPDATE sessions
SET idle_expires_at = COALESCE(idle_expires_at, expires_at),
    absolute_expires_at = COALESCE(absolute_expires_at, expires_at)
WHERE idle_expires_at IS NULL OR absolute_expires_at IS NULL;

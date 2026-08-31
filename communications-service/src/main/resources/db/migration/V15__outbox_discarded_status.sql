-- A terminal, auditable state for messages deliberately suppressed because
-- they have no valid consumer. It is distinct from PUBLISHED and FAILED.
ALTER TYPE outbox_status ADD VALUE IF NOT EXISTS 'DISCARDED';

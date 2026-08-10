-- Enum additions only. Columns, constraints and data repair belong in V12.
ALTER TYPE email_delivery_status ADD VALUE IF NOT EXISTS 'WAITING_FOR_RECIPIENT';
ALTER TYPE email_delivery_status ADD VALUE IF NOT EXISTS 'INDETERMINATE';
ALTER TYPE email_attempt_status ADD VALUE IF NOT EXISTS 'READY_TO_SEND';
ALTER TYPE email_attempt_status ADD VALUE IF NOT EXISTS 'CALL_STARTED';
ALTER TYPE email_attempt_status ADD VALUE IF NOT EXISTS 'INDETERMINATE';

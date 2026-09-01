-- Historical BFF versions discarded institutional_email. Rebuild Person Presentation from Users.
UPDATE projection_status
SET status = 'BOOTSTRAPPING',
    last_applied_at = NULL,
    last_error_code = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE projection_name = 'person';

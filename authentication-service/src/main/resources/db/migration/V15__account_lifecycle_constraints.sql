ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_deactivation_reason_code
        CHECK (deactivation_reason_code IS NULL OR deactivation_reason_code IN ('ACADEMIC_ABSENCE', 'ADMINISTRATIVE', 'SECURITY'));

ALTER TABLE account_status_history
    ADD CONSTRAINT ck_account_status_history_reason_for_inactive
        CHECK (new_status <> 'INACTIVE' OR previous_status IS NULL OR reason_code IS NOT NULL),
    ADD CONSTRAINT ck_account_status_history_reason_code
        CHECK (reason_code IS NULL OR reason_code IN ('ACADEMIC_ABSENCE', 'ADMINISTRATIVE', 'SECURITY'));

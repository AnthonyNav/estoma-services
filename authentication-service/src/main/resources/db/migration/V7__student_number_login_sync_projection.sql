-- student_number_login_sync_projection — technical state tracking the
-- ordered synchronization of a MATRICULA account's loginIdentifier from
-- academic-service's StudentNumberCorrected. Not Consumer Inbox, not a
-- foreign-service projection: account_id is a logical reference only (no
-- physical FK), so a conflict can still be recorded even when the account
-- does not exist locally (e.g. ACCOUNT_NOT_FOUND_FOR_STUDENT).
--
-- Source: authentication-service/docs/system_contract.md, platform docs
-- data-model.md §9.1.

CREATE TABLE student_number_login_sync_projection (
    account_id                             uuid PRIMARY KEY,
    student_academic_profile_id              uuid NOT NULL,
    last_applied_student_number_version        integer NOT NULL DEFAULT 0,
    synchronization_status                       student_number_sync_status NOT NULL DEFAULT 'PENDING',
    last_error_code                                varchar(80),
    updated_at                                       timestamptz NOT NULL,

    CONSTRAINT ck_student_number_sync_version_non_negative
        CHECK (last_applied_student_number_version >= 0)
);

CREATE INDEX ix_student_number_sync_profile ON student_number_login_sync_projection (student_academic_profile_id);
CREATE INDEX ix_student_number_sync_status ON student_number_login_sync_projection (synchronization_status);

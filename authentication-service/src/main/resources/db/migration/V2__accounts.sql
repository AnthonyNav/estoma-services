-- accounts — the aggregate root this service owns: one independent access
-- identity per (personId, role), with its own login identifier, status,
-- lockout state, and version.
--
-- Source: authentication-service/docs/system_contract.md ("Mandatory
-- Invariants"), platform docs data-model.md §5.

CREATE TABLE accounts (
    account_id                      uuid PRIMARY KEY,
    person_id                       uuid NOT NULL,

    login_type                      login_type NOT NULL,
    login_identifier                varchar(160) NOT NULL,
    login_identifier_normalized     varchar(160) NOT NULL,

    status                          account_status NOT NULL DEFAULT 'INACTIVE',

    failed_attempts                 integer NOT NULL DEFAULT 0,
    locked_until                    timestamptz,
    must_change_password            boolean NOT NULL DEFAULT false,
    temporary_password_expires_at   timestamptz,

    version                         integer NOT NULL DEFAULT 1,

    created_at                      timestamptz NOT NULL,
    updated_at                      timestamptz NOT NULL,
    deactivated_at                  timestamptz,

    CONSTRAINT ck_accounts_version_positive
        CHECK (version > 0),
    CONSTRAINT ck_accounts_failed_attempts_non_negative
        CHECK (failed_attempts >= 0)
);

-- CreateAccount + ActivateAccount is the only path to ACTIVE (system_contract.md
-- "Mandatory Invariants"): loginType + normalized login identifier is unique
-- across every account, regardless of status.
CREATE UNIQUE INDEX uq_accounts_login ON accounts (login_type, login_identifier_normalized);

CREATE INDEX ix_accounts_person ON accounts (person_id);
CREATE INDEX ix_accounts_status ON accounts (status);

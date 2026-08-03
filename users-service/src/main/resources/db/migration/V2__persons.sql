-- persons — the official source of personal identity data.
--
-- Source: estomatologia-platform-docs
--   03-services/shared/users-service/data-model.md §4, §5, §8

CREATE TABLE persons (
    person_id                      uuid PRIMARY KEY,

    first_name                     varchar(120) NOT NULL,
    paternal_surname               varchar(120) NOT NULL,
    maternal_surname               varchar(120),

    institutional_email            varchar(320) NOT NULL,
    institutional_email_normalized varchar(320) NOT NULL,

    phone                          varchar(30),

    status                         person_status NOT NULL DEFAULT 'ACTIVE',
    version                        integer NOT NULL DEFAULT 1,

    created_at                     timestamptz NOT NULL,
    updated_at                     timestamptz NOT NULL,
    deactivated_at                 timestamptz,

    CONSTRAINT ck_persons_first_name_not_blank
        CHECK (length(btrim(first_name)) > 0),
    CONSTRAINT ck_persons_paternal_not_blank
        CHECK (length(btrim(paternal_surname)) > 0),
    CONSTRAINT ck_persons_maternal_not_blank
        CHECK (maternal_surname IS NULL OR length(btrim(maternal_surname)) > 0),
    CONSTRAINT ck_persons_version_positive
        CHECK (version > 0),
    CONSTRAINT ck_persons_email_normalized
        CHECK (institutional_email_normalized = lower(btrim(institutional_email))),
    -- data-model.md §8 rules 5 and 6 as one biconditional: an inactive
    -- person MUST have deactivated_at; an active person MUST NOT.
    CONSTRAINT ck_persons_deactivation_state
        CHECK (
            (status = 'INACTIVE' AND deactivated_at IS NOT NULL)
            OR (status = 'ACTIVE' AND deactivated_at IS NULL)
        )
);

CREATE INDEX ix_persons_email_normalized ON persons (institutional_email_normalized);
CREATE INDEX ix_persons_status ON persons (status);

-- Interim DUPLICATE_PERSON rule (docs/system-contract.md §6): one
-- institutional mailbox belongs to exactly one Person, active or inactive.
CREATE UNIQUE INDEX ux_persons_email_normalized ON persons (institutional_email_normalized);

-- person_id is immutable (data-model.md §8 rule 1). Enforced with a
-- trigger so the invariant survives any future writer, not just the
-- application code that exists today.
CREATE FUNCTION prevent_person_id_change() RETURNS trigger AS $$
BEGIN
    IF NEW.person_id <> OLD.person_id THEN
        RAISE EXCEPTION 'person_id is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_persons_id_immutable
    BEFORE UPDATE ON persons
    FOR EACH ROW
    EXECUTE FUNCTION prevent_person_id_change();

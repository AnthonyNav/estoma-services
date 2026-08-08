-- person_status_projection — this service's own read model of whether a
-- Person is globally active, kept current by consuming PersonCreated/
-- Activated/Deactivated from users-service. Not the source of truth, no
-- physical FK to users-db (data-model.md §14).
--
-- Source: authentication-service/docs/system_contract.md, platform docs
-- data-model.md §9.

CREATE TABLE person_status_projection (
    person_id        uuid PRIMARY KEY,
    status              person_status NOT NULL,
    source_version         integer NOT NULL,
    updated_at                timestamptz NOT NULL,

    CONSTRAINT ck_person_status_projection_source_version_non_negative
        CHECK (source_version >= 0)
);

-- recipient_projection — derived, read-only view of a recipient built from
-- Person (users-service) and Account (authentication-service) events. Not
-- a source of truth; used only to validate/enrich RequestCommunication.
--
-- Source: communications-service/docs/system_contract.md ("Ownership",
-- "Events Consumed"), platform docs data-model.md §3, §8.

CREATE TABLE recipient_projection (
    account_id                uuid PRIMARY KEY,
    person_id                 uuid NOT NULL,

    display_name               varchar(200),
    institutional_email          varchar(320),

    person_status                  projected_status NOT NULL,
    account_status                   projected_status NOT NULL,

    person_source_version              integer NOT NULL,
    account_source_version               integer NOT NULL,

    updated_at                             timestamptz NOT NULL
);

CREATE INDEX ix_recipient_projection_person ON recipient_projection (person_id);

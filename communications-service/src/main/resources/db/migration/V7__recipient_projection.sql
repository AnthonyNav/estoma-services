-- recipient_projection — derived, read-only view of a recipient built from
-- Person (users-service) and Account (authentication-service) events. Not
-- a source of truth; used only to validate/enrich RequestCommunication.
--
-- The row is created by an Account event (the only one that carries a
-- concrete account_id); a Person can own more than one account
-- (shared-platform-business.md §8.1: "dos cuentas de la misma Persona
-- tienen buzones independientes"), so a Person event fans out to every
-- existing row for that person_id via UPDATE, not a keyed upsert. This
-- means the person-derived columns are legitimately absent until the
-- corresponding Person event has been consumed for an already-created
-- account row -- hence nullable, unlike the account-derived columns.
--
-- Source: communications-service/docs/system_contract.md ("Ownership",
-- "Events Consumed"), platform docs data-model.md §3, §8.

CREATE TABLE recipient_projection (
    account_id                uuid PRIMARY KEY,
    person_id                 uuid NOT NULL,

    display_name               varchar(200),
    institutional_email          varchar(320),

    person_status                  projected_status,
    account_status                   projected_status NOT NULL,

    person_source_version              integer,
    account_source_version               integer NOT NULL,

    updated_at                             timestamptz NOT NULL
);

CREATE INDEX ix_recipient_projection_person ON recipient_projection (person_id);

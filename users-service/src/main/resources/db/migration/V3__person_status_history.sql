-- person_status_history — append-only log of activations/deactivations.
--
-- Source: estomatologia-platform-docs
--   03-services/shared/users-service/data-model.md §4, §7
--
-- No physical FK on changed_by_account_id: it is a logical reference to an
-- authentication-service Account, which lives in a different database
-- (data-model.md §7 forbids a physical FK here).

CREATE TABLE person_status_history (
    status_change_id      uuid PRIMARY KEY,
    person_id              uuid NOT NULL REFERENCES persons (person_id),

    previous_status         person_status,
    new_status              person_status NOT NULL,

    changed_by_account_id   uuid,
    correlation_id           uuid,

    changed_at               timestamptz NOT NULL
);

CREATE INDEX ix_person_status_history ON person_status_history (person_id, changed_at);

-- Completes DeactivatePerson's V1 contract on person_status_history:
-- reason_code (mandatory when new_status = INACTIVE) and reason_detail
-- (mandatory only when reason_code = OTHER).
--
-- Source: estomatologia-platform-docs
--   03-services/shared/users-service/data-model.md §5, §7
--   03-services/shared/users-service/commands.md §7.1 (reason_code catalog)
--
-- data-model.md §5 fixes the column types exactly -- varchar, not a
-- Postgres enum -- and explicitly leaves the CHECK constraints to this
-- migration ("la restricción exacta ... queda para la migración").

ALTER TABLE person_status_history
    ADD COLUMN reason_code   varchar(40),
    ADD COLUMN reason_detail varchar(500);

-- NOT VALID: existing rows (all activations, all pre-this-round
-- deactivations) are grandfathered in rather than backfilled with an
-- invented reason_code -- only rows written from here on are checked.
ALTER TABLE person_status_history
    ADD CONSTRAINT ck_person_status_history_reason_required_for_inactive
    CHECK (new_status <> 'INACTIVE' OR reason_code IS NOT NULL) NOT VALID;

ALTER TABLE person_status_history
    ADD CONSTRAINT ck_person_status_history_reason_detail_required_for_other
    CHECK (reason_code <> 'OTHER' OR (reason_detail IS NOT NULL AND btrim(reason_detail) <> '')) NOT VALID;

ALTER TABLE person_status_history
    ADD CONSTRAINT ck_person_status_history_reason_code_catalog
    CHECK (
        reason_code IS NULL
        OR reason_code IN ('DUPLICATE', 'CREATED_BY_ERROR', 'IDENTITY_CORRECTION', 'ADMINISTRATIVE_REQUEST', 'OTHER')
    ) NOT VALID;

-- inbox_visits — last time an account visited its inbox for a given
-- system. Composite primary key: an account has one independent inbox per
-- system_code.
--
-- Source: platform docs data-model.md §3, §5, §8.

CREATE TABLE inbox_visits (
    account_id          uuid NOT NULL,
    system_code          varchar(80) NOT NULL,

    last_visited_at        timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,

    PRIMARY KEY (account_id, system_code)
);

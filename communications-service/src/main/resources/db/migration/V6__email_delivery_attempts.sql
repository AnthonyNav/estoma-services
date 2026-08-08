-- email_delivery_attempts — one row per provider interaction for a given
-- email delivery.
--
-- Source: platform docs data-model.md §3, §6, §8.

CREATE TABLE email_delivery_attempts (
    email_delivery_attempt_id     uuid PRIMARY KEY,
    email_delivery_id             uuid NOT NULL REFERENCES email_deliveries (email_delivery_id),

    attempt_number                  integer NOT NULL,
    provider                          varchar(80) NOT NULL,
    delivery_mode                       email_content_mode NOT NULL,
    status                                 email_attempt_status NOT NULL,
    provider_message_id                      varchar(300),
    normalized_error_code                      varchar(120),
    error_message                                varchar(1000),

    started_at                                     timestamptz NOT NULL,
    completed_at                                     timestamptz NOT NULL,

    CONSTRAINT uq_email_delivery_attempt
        UNIQUE (email_delivery_id, attempt_number)
);

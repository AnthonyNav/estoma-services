-- email_deliveries — the prepared email a communication generates, at most
-- one per communication, and its current delivery state.
--
-- Source: communications-service/docs/system_contract.md ("Mandatory
-- Invariants": "a communication generates at most one email", "ACCEPTED
-- does not mean physical delivery"), platform docs data-model.md §3, §6,
-- §8, commands.md §3-§4.

CREATE TABLE email_deliveries (
    email_delivery_id      uuid PRIMARY KEY,
    communication_id       uuid NOT NULL UNIQUE REFERENCES communications (communication_id),

    recipient_address       varchar(320) NOT NULL,
    recipient_name              varchar(200),

    from_name                     varchar(200) NOT NULL,
    from_address                    varchar(320) NOT NULL,
    reply_to_address                  varchar(320),

    content_mode                        email_content_mode NOT NULL,
    subject                                varchar(300),
    text_body                                text,
    html_body                                  text,
    template_code                              varchar(120),
    template_data                                jsonb,

    status                                         email_delivery_status NOT NULL DEFAULT 'PENDING',
    attempt_count                                    integer NOT NULL DEFAULT 0,
    next_attempt_at                                    timestamptz,

    accepted_at                                          timestamptz,
    delivered_at                                           timestamptz,
    failed_at                                                timestamptz,
    last_error_code                                            varchar(120),
    last_error_message                                           varchar(1000),

    created_at                                                     timestamptz NOT NULL,
    updated_at                                                       timestamptz NOT NULL,

    CONSTRAINT ck_email_deliveries_attempt_count_non_negative
        CHECK (attempt_count >= 0)
);

-- Claim query for the delivery worker (system_contract.md "Email Delivery
-- Operational Policy"): PENDING/RETRY_SCHEDULED rows due for an attempt.
CREATE INDEX ix_email_deliveries_claim ON email_deliveries (status, next_attempt_at);

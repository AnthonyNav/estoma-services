-- Password recovery delivery metadata. The recovery code/token is never persisted.
ALTER TABLE email_deliveries
    ALTER COLUMN recipient_address DROP NOT NULL,
    ADD COLUMN recovery_id uuid,
    ADD COLUMN recovery_expires_at timestamptz;

ALTER TABLE email_deliveries
    ADD CONSTRAINT ck_email_delivery_recovery_metadata_pair
      CHECK ((recovery_id IS NULL) = (recovery_expires_at IS NULL)),
    ADD CONSTRAINT ck_email_delivery_waiting_recipient_is_recovery
      CHECK (status <> CAST('WAITING_FOR_RECIPIENT' AS email_delivery_status) OR recovery_id IS NOT NULL),
    ADD CONSTRAINT ck_email_delivery_non_recovery_requires_recipient
      CHECK (recovery_id IS NOT NULL OR recipient_address IS NOT NULL),
    ADD CONSTRAINT ck_email_delivery_recovery_content_never_persisted
      CHECK (
        recovery_id IS NULL OR (
          content_mode = CAST('TEMPLATE' AS email_content_mode)
          AND template_code = 'PASSWORD_RECOVERY'
          AND template_data IS NULL
          AND subject IS NULL
          AND text_body IS NULL
          AND html_body IS NULL
        )
      );

CREATE UNIQUE INDEX ux_email_deliveries_recovery_id
    ON email_deliveries (recovery_id)
    WHERE recovery_id IS NOT NULL;

CREATE INDEX ix_email_deliveries_recovery_expiry
    ON email_deliveries (recovery_expires_at)
    WHERE recovery_id IS NOT NULL AND status IN (
      CAST('WAITING_FOR_RECIPIENT' AS email_delivery_status),
      CAST('PENDING' AS email_delivery_status),
      CAST('RETRY_SCHEDULED' AS email_delivery_status));

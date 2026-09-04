ALTER TABLE appointment_read_model
    ADD COLUMN exceptional_authorization_id UUID,
    ADD COLUMN exceptional_authorization_context_known BOOLEAN NOT NULL DEFAULT FALSE;

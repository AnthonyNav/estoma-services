ALTER TABLE exceptional_appointment_authorization_read_model
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN policy_context_known BOOLEAN NOT NULL DEFAULT FALSE;

-- Independent rows preserve supersession when successor events arrive before predecessor grants.
CREATE TABLE exceptional_authorization_supersession_read_model (
    authorization_id UUID PRIMARY KEY,
    successor_authorization_id UUID NOT NULL
);

-- `AppointmentStarted` is the owner event for the public V1 `IN_PROGRESS` state.
-- Repair rows projected by the pre-PR6 mapper before serving Home QR contexts.
UPDATE appointment_read_model
SET status = 'IN_PROGRESS'
WHERE status = 'STARTED';

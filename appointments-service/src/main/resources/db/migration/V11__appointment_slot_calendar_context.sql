ALTER TABLE appointment_time_slots ADD COLUMN calendar_id uuid;

UPDATE appointment_time_slots slot
SET calendar_id = schedule.calendar_id
FROM service_schedule_projection schedule
WHERE schedule.schedule_id = slot.source_schedule_id
  AND slot.calendar_id IS NULL;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM appointment_time_slots WHERE calendar_id IS NULL) THEN
    RAISE EXCEPTION 'appointment_time_slots contains rows without authoritative calendar_id';
  END IF;
END $$;

ALTER TABLE appointment_time_slots ALTER COLUMN calendar_id SET NOT NULL;
CREATE INDEX ix_appointment_slots_calendar_date ON appointment_time_slots (calendar_id, service_date, starts_at);

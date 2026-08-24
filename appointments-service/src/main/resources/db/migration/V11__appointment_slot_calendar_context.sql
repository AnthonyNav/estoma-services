ALTER TABLE appointment_time_slots ADD COLUMN calendar_id uuid;

-- Opportunistic initial backfill. A projection may legitimately be REBUILDING during deployment, so
-- the migration must not fail merely because the owner snapshot has not been replayed yet.
UPDATE appointment_time_slots slot
SET calendar_id = schedule.calendar_id
FROM service_schedule_projection schedule
WHERE schedule.schedule_id = slot.source_schedule_id
  AND slot.calendar_id IS NULL;

-- Every subsequent schedule snapshot/event repairs any historical slot that could not be backfilled
-- during Flyway. New AppointmentTimeSlot writes always provide calendar_id from Application.
CREATE OR REPLACE FUNCTION appointments_backfill_slot_calendar()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  UPDATE appointment_time_slots
  SET calendar_id = NEW.calendar_id
  WHERE source_schedule_id = NEW.schedule_id
    AND calendar_id IS NULL;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_backfill_slot_calendar
AFTER INSERT OR UPDATE OF calendar_id ON service_schedule_projection
FOR EACH ROW EXECUTE FUNCTION appointments_backfill_slot_calendar();

CREATE INDEX ix_appointment_slots_calendar_date
    ON appointment_time_slots (calendar_id, service_date, starts_at);

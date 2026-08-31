ALTER TABLE systems
  ADD COLUMN version integer NOT NULL DEFAULT 1,
  ADD CONSTRAINT ck_systems_version CHECK (version > 0);

CREATE OR REPLACE FUNCTION increment_system_catalog_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    NEW.version := OLD.version + 1;
    NEW.updated_at := clock_timestamp();
  ELSIF NEW.version IS DISTINCT FROM OLD.version THEN
    RAISE EXCEPTION 'systems.version is managed when status changes';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_systems_increment_version
BEFORE UPDATE ON systems
FOR EACH ROW
EXECUTE FUNCTION increment_system_catalog_version();

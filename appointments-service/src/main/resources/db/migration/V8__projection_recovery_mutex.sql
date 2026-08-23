-- A FULL projection rebuild deletes and rehydrates owner snapshots while the event queue remains bound.
-- Serialize writes per owner with a PostgreSQL transaction-level advisory lock so reset/snapshot/event
-- transactions cannot interleave destructively. Different owners retain independent concurrency.

CREATE OR REPLACE FUNCTION appointments_lock_academic_projection()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('appointments:projection:academic', 0));
  RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE OR REPLACE FUNCTION appointments_lock_authorization_projection()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('appointments:projection:authorization', 0));
  RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE OR REPLACE FUNCTION appointments_lock_wash_administration_projection()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('appointments:projection:wash-administration', 0));
  RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE OR REPLACE FUNCTION appointments_lock_wash_execution_projection()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('appointments:projection:wash-execution', 0));
  RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER trg_lock_academic_period_projection
BEFORE INSERT OR UPDATE OR DELETE ON academic_period_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_academic_projection();
CREATE TRIGGER trg_lock_student_academic_profile_projection
BEFORE INSERT OR UPDATE OR DELETE ON student_academic_profile_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_academic_projection();
CREATE TRIGGER trg_lock_course_section_projection
BEFORE INSERT OR UPDATE OR DELETE ON course_section_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_academic_projection();
CREATE TRIGGER trg_lock_student_course_section_projection
BEFORE INSERT OR UPDATE OR DELETE ON student_course_section_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_academic_projection();

CREATE TRIGGER trg_lock_account_role_projection
BEFORE INSERT OR UPDATE OR DELETE ON account_role_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_authorization_projection();
CREATE TRIGGER trg_lock_wash_system_access_projection
BEFORE INSERT OR UPDATE OR DELETE ON wash_system_access_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_authorization_projection();

CREATE TRIGGER trg_lock_service_calendar_projection
BEFORE INSERT OR UPDATE OR DELETE ON service_calendar_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_wash_administration_projection();
CREATE TRIGGER trg_lock_service_schedule_projection
BEFORE INSERT OR UPDATE OR DELETE ON service_schedule_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_wash_administration_projection();
CREATE TRIGGER trg_lock_administrative_closure_projection
BEFORE INSERT OR UPDATE OR DELETE ON administrative_closure_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_wash_administration_projection();
CREATE TRIGGER trg_lock_cabin_projection
BEFORE INSERT OR UPDATE OR DELETE ON cabin_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_wash_administration_projection();
CREATE TRIGGER trg_lock_tank_projection
BEFORE INSERT OR UPDATE OR DELETE ON tank_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_wash_administration_projection();
CREATE TRIGGER trg_lock_resource_priority_projection
BEFORE INSERT OR UPDATE OR DELETE ON resource_priority_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_wash_administration_projection();

CREATE TRIGGER trg_lock_resource_unavailability_projection
BEFORE INSERT OR UPDATE OR DELETE ON resource_unavailability_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_wash_execution_projection();
CREATE TRIGGER trg_lock_cleaning_period_projection
BEFORE INSERT OR UPDATE OR DELETE ON cleaning_period_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_wash_execution_projection();
CREATE TRIGGER trg_lock_wash_execution_evidence_projection
BEFORE INSERT OR UPDATE OR DELETE ON wash_execution_evidence_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_wash_execution_projection();
CREATE TRIGGER trg_lock_wash_execution_transition_projection
BEFORE INSERT OR UPDATE OR DELETE ON wash_execution_transition_projection
FOR EACH STATEMENT EXECUTE FUNCTION appointments_lock_wash_execution_projection();

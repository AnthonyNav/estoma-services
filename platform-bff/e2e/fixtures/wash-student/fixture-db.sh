#!/bin/sh
set -eu

mode=${1:-}
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
platform_dir=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
report_dir=${E2E_REPORT_DIR:-$platform_dir/target/e2e/wash-student}
manifest=${E2E_FIXTURE_MANIFEST:-$report_dir/fixture.env}
bff_database=${E2E_BFF_DATABASE:-platform_bff_db}
appointments_database=${E2E_APPOINTMENTS_DATABASE:-appointments_db}

fail() {
  printf 'wash-student fixture: %s\n' "$*" >&2
  exit 1
}

require_runtime() {
  : "${E2E_PSQL_EXEC:?E2E_PSQL_EXEC must name an executable that accepts a database and reads SQL on stdin}"
  [ -x "$E2E_PSQL_EXEC" ] || fail "E2E_PSQL_EXEC is not executable"
}

sql() {
  database=$1
  "$E2E_PSQL_EXEC" "$database"
}

require_uuid() {
  value=$1
  name=$2
  case "$value" in
    *[!0-9A-Fa-f-]*) fail "$name must be a UUID" ;;
    ????????-????-????-????-????????????) ;;
    *) fail "$name must be a UUID" ;;
  esac
}

require_fixture_login() {
  value=$1
  case "$value" in e2e-*) ;; *) fail "fixture login identifier must begin with e2e-" ;; esac
  case "$value" in *[!A-Za-z0-9._@+-]*) fail "fixture login identifier contains unsafe characters" ;; esac
}

new_uuid() {
  sed -n '1p' /proc/sys/kernel/random/uuid
}

manifest_value() {
  key=$1
  value=$(sed -n "s/^${key}=//p" "$manifest")
  [ -n "$value" ] || fail "manifest is missing $key"
  [ "$(printf '%s\n' "$value" | wc -l)" -eq 1 ] || fail "manifest repeats $key"
  printf '%s\n' "$value"
}

bootstrap() {
  require_runtime
  : "${E2E_ACCOUNT_ID:?E2E_ACCOUNT_ID is required}"
  : "${E2E_LOGIN_IDENTIFIER:?E2E_LOGIN_IDENTIFIER is required}"
  require_uuid "$E2E_ACCOUNT_ID" E2E_ACCOUNT_ID
  require_fixture_login "$E2E_LOGIN_IDENTIFIER"
  [ ! -e "$manifest" ] || fail "fixture manifest already exists; run cleanup first"

  person_id=$(sql "$bff_database" <<SQL
SELECT person_id
FROM account_presentation_read_model
WHERE account_id = '$E2E_ACCOUNT_ID'::uuid
  AND login_identifier = '$E2E_LOGIN_IDENTIFIER'
  AND account_status = 'ACTIVE';
SQL
  )
  require_uuid "$person_id" 'active BFF account presentation person_id'

  person_ready=$(sql "$bff_database" <<SQL
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM person_presentation_read_model
  WHERE person_id = '$person_id'::uuid AND status = 'ACTIVE'
) THEN 'yes' ELSE 'no' END;
SQL
  )
  [ "$person_ready" = yes ] || fail "the synthetic account needs an active Users person projection"

  bff_role=$(sql "$bff_database" <<SQL
SELECT COALESCE((SELECT role_code FROM authorization_role_projection
  WHERE account_id = '$E2E_ACCOUNT_ID'::uuid), '<absent>');
SQL
  )
  owner_role=$(sql "$appointments_database" <<SQL
SELECT COALESCE((SELECT role_code FROM account_role_projection
  WHERE account_id = '$E2E_ACCOUNT_ID'::uuid), '<absent>');
SQL
  )
  case "$bff_role" in '<absent>'|'E2E_TESTER') ;; *) fail "BFF role is not an isolated E2E_TESTER fixture" ;; esac
  case "$owner_role" in '<absent>'|'E2E_TESTER') ;; *) fail "Appointments role is not an isolated E2E_TESTER fixture" ;; esac

  period_id=$(sql "$bff_database" <<SQL
SELECT academic_period_id FROM academic_period_read_model
WHERE status = 'ACTIVE'
ORDER BY updated_at DESC, academic_period_id
LIMIT 1;
SQL
  )
  require_uuid "$period_id" 'active BFF academic period'
  owner_period_id=$(sql "$appointments_database" <<SQL
SELECT academic_period_id FROM academic_period_projection
WHERE status = 'ACTIVE' AND reliable = TRUE
ORDER BY updated_at DESC, academic_period_id
LIMIT 1;
SQL
  )
  [ "$owner_period_id" = "$period_id" ] || fail "BFF and Appointments do not agree on the active academic period"

  system_id=$(sql "$bff_database" <<SQL
SELECT system_id FROM authorization_system_catalog_read_model
WHERE system_code = 'LAVADO_ULTRASONICO' AND status = 'ACTIVE' AND trusted = TRUE;
SQL
  )
  require_uuid "$system_id" 'trusted Lavado system catalog id'

  sql "$bff_database" >/dev/null <<SQL
DO \$guard\$
BEGIN
  IF EXISTS (SELECT 1 FROM authorization_access_projection
      WHERE account_id = '$E2E_ACCOUNT_ID'::uuid AND system_code = 'LAVADO_ULTRASONICO') THEN
    RAISE EXCEPTION 'synthetic account already has BFF Lavado access';
  END IF;
  IF EXISTS (SELECT 1 FROM student_academic_profile_read_model
      WHERE account_id = '$E2E_ACCOUNT_ID'::uuid) THEN
    RAISE EXCEPTION 'synthetic account already has a BFF academic profile';
  END IF;
  IF EXISTS (
      SELECT 1 FROM appointment_read_model appointment
      JOIN appointment_time_slot_read_model slot
        ON slot.appointment_time_slot_id = appointment.appointment_time_slot_id
      WHERE appointment.student_account_id = '$E2E_ACCOUNT_ID'::uuid
        AND slot.service_date = (CURRENT_TIMESTAMP AT TIME ZONE 'America/Mexico_City')::date) THEN
    RAISE EXCEPTION 'synthetic account already has a BFF appointment for today';
  END IF;
  IF (SELECT count(*) FROM projection_status
      WHERE projection_name IN ('person','academic','authorization','system-catalog','appointments','wash-execution')
        AND status = 'READY') <> 6 THEN
    RAISE EXCEPTION 'required BFF projections are not READY';
  END IF;
  IF to_regclass('appointment_availability_refresh') IS NULL
      OR to_regclass('appointment_availability_read_model') IS NULL
      OR to_regclass('appointment_availability_slot_read_model') IS NULL THEN
    RAISE EXCEPTION 'BFF appointment availability migration is not deployed';
  END IF;
END
\$guard\$;
SQL

  sql "$appointments_database" >/dev/null <<SQL
DO \$guard\$
BEGIN
  IF EXISTS (SELECT 1 FROM wash_system_access_projection
      WHERE account_id = '$E2E_ACCOUNT_ID'::uuid AND system_code = 'LAVADO_ULTRASONICO') THEN
    RAISE EXCEPTION 'synthetic account already has Appointments Lavado access';
  END IF;
  IF EXISTS (SELECT 1 FROM student_academic_profile_projection
      WHERE student_account_id = '$E2E_ACCOUNT_ID'::uuid) THEN
    RAISE EXCEPTION 'synthetic account already has an Appointments academic profile';
  END IF;
  IF EXISTS (
      SELECT 1 FROM appointments appointment
      JOIN appointment_time_slots slot USING (appointment_time_slot_id)
      WHERE appointment.student_account_id = '$E2E_ACCOUNT_ID'::uuid
        AND slot.service_date = (CURRENT_TIMESTAMP AT TIME ZONE 'America/Mexico_City')::date) THEN
    RAISE EXCEPTION 'synthetic account already has an Appointments appointment for today';
  END IF;
  IF EXISTS (SELECT 1 FROM (
      SELECT reliable FROM academic_period_projection
      UNION ALL SELECT reliable FROM student_academic_profile_projection
      UNION ALL SELECT reliable FROM course_section_projection
      UNION ALL SELECT reliable FROM student_course_section_projection
      UNION ALL SELECT reliable FROM account_role_projection
      UNION ALL SELECT reliable FROM wash_system_access_projection
    ) projection WHERE reliable = FALSE) THEN
    RAISE EXCEPTION 'Appointments has an unreliable academic or authorization projection';
  END IF;
  IF (CURRENT_TIMESTAMP AT TIME ZONE 'America/Mexico_City')::time >= TIME '23:15:00' THEN
    RAISE EXCEPTION 'not enough time remains in the service date for a safe E2E slot';
  END IF;
END
\$guard\$;
SQL

  academic_profile_id=$(new_uuid)
  course_section_id=$(new_uuid)
  system_access_id=$(new_uuid)
  slot_id=$(new_uuid)
  schedule_id=$(new_uuid)
  calendar_id=$(new_uuid)
  suffix=$(printf '%s' "$course_section_id" | tr -d '-' | cut -c1-10)
  run_key="e2e-wash-$suffix"
  nrc="E2E-$suffix"

  times=$(sql "$appointments_database" <<SQL
SELECT
  (CURRENT_TIMESTAMP AT TIME ZONE 'America/Mexico_City')::date || '|' ||
  (CURRENT_TIMESTAMP + INTERVAL '40 minutes')::text || '|' ||
  (CURRENT_TIMESTAMP + INTERVAL '70 minutes')::text || '|' ||
  (CURRENT_TIMESTAMP + INTERVAL '30 minutes')::text || '|' ||
  (CURRENT_TIMESTAMP + INTERVAL '25 minutes')::text || '|' ||
  (CURRENT_TIMESTAMP + INTERVAL '50 minutes')::text;
SQL
  )
  prior_ifs=$IFS
  IFS='|'
  set -- $times
  IFS=$prior_ifs
  [ "$#" -eq 6 ] || fail "could not derive the isolated slot window"
  service_date=$1
  starts_at=$2
  ends_at=$3
  booking_deadline_at=$4
  cancellation_deadline_at=$5
  arrival_tolerance_until=$6

  mkdir -p "$report_dir"
  chmod 700 "$report_dir"
  umask 077
  {
    printf 'ACCOUNT_ID=%s\n' "$E2E_ACCOUNT_ID"
    printf 'LOGIN_IDENTIFIER=%s\n' "$E2E_LOGIN_IDENTIFIER"
    printf 'PERSON_ID=%s\n' "$person_id"
    printf 'ACADEMIC_PROFILE_ID=%s\n' "$academic_profile_id"
    printf 'COURSE_SECTION_ID=%s\n' "$course_section_id"
    printf 'SYSTEM_ACCESS_ID=%s\n' "$system_access_id"
    printf 'SYSTEM_ID=%s\n' "$system_id"
    printf 'PERIOD_ID=%s\n' "$period_id"
    printf 'SLOT_ID=%s\n' "$slot_id"
    printf 'SCHEDULE_ID=%s\n' "$schedule_id"
    printf 'CALENDAR_ID=%s\n' "$calendar_id"
    printf 'RUN_KEY=%s\n' "$run_key"
    printf 'NRC=%s\n' "$nrc"
    printf 'BFF_ROLE_WAS_ABSENT=%s\n' "$([ "$bff_role" = '<absent>' ] && printf 1 || printf 0)"
    printf 'OWNER_ROLE_WAS_ABSENT=%s\n' "$([ "$owner_role" = '<absent>' ] && printf 1 || printf 0)"
  } > "$manifest"

  cleanup_on_error() {
    trap - EXIT INT TERM
    "$0" cleanup >/dev/null 2>&1 || true
  }
  trap cleanup_on_error EXIT INT TERM

  sql "$appointments_database" >/dev/null <<SQL
BEGIN;
INSERT INTO account_role_projection
  (account_id, role_id, role_code, source_version, updated_at, reliable)
VALUES ('$E2E_ACCOUNT_ID'::uuid, NULL, 'ALUMNO', 1, CURRENT_TIMESTAMP, TRUE)
ON CONFLICT (account_id) DO UPDATE
SET role_code = 'ALUMNO', updated_at = CURRENT_TIMESTAMP, reliable = TRUE;

INSERT INTO wash_system_access_projection
  (system_access_id, account_id, system_id, system_code, access_status, source_version, updated_at, reliable)
VALUES ('$system_access_id'::uuid, '$E2E_ACCOUNT_ID'::uuid, '$system_id'::uuid,
  'LAVADO_ULTRASONICO', 'ACTIVE', 1, CURRENT_TIMESTAMP, TRUE);

INSERT INTO student_academic_profile_projection
  (academic_profile_id, student_account_id, current_semester, academic_status, source_version, updated_at, reliable)
VALUES ('$academic_profile_id'::uuid, '$E2E_ACCOUNT_ID'::uuid, 1, 'ACTIVE', 1, CURRENT_TIMESTAMP, TRUE);

INSERT INTO course_section_projection
  (course_section_id, academic_period_id, status, source_version, updated_at, reliable)
VALUES ('$course_section_id'::uuid, '$period_id'::uuid, 'ACTIVE', 1, CURRENT_TIMESTAMP, TRUE);

INSERT INTO student_course_section_projection
  (academic_profile_id, student_account_id, course_section_id, academic_period_id,
   enrolled_at, removed_at, source_version, updated_at, reliable)
VALUES ('$academic_profile_id'::uuid, '$E2E_ACCOUNT_ID'::uuid, '$course_section_id'::uuid,
  '$period_id'::uuid, CURRENT_TIMESTAMP, NULL, 1, CURRENT_TIMESTAMP, TRUE);

INSERT INTO appointment_time_slots
  (appointment_time_slot_id, source_schedule_id, calendar_id, service_date, starts_at, ends_at,
   timezone, booking_deadline_at, cancellation_deadline_at, arrival_tolerance_until,
   status, version, created_at, updated_at)
VALUES ('$slot_id'::uuid, '$schedule_id'::uuid, '$calendar_id'::uuid, '$service_date'::date,
  '$starts_at'::timestamptz, '$ends_at'::timestamptz, 'America/Mexico_City',
  '$booking_deadline_at'::timestamptz, '$cancellation_deadline_at'::timestamptz,
  '$arrival_tolerance_until'::timestamptz, 'ACTIVE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO appointment_capacity_projections
  (appointment_time_slot_id, normal_capacity, journey_capacity,
   immunocompromised_capacity, version, calculated_at)
VALUES ('$slot_id'::uuid, 4, 0, 1, 1, CURRENT_TIMESTAMP);
COMMIT;
SQL

  sql "$bff_database" >/dev/null <<SQL
BEGIN;
INSERT INTO authorization_role_projection
  (account_id, role_code, role_source_version, updated_at)
VALUES ('$E2E_ACCOUNT_ID'::uuid, 'ALUMNO', 1, CURRENT_TIMESTAMP)
ON CONFLICT (account_id) DO UPDATE
SET role_code = 'ALUMNO', updated_at = CURRENT_TIMESTAMP;

INSERT INTO authorization_access_projection
  (system_access_id, account_id, system_id, system_code, status, access_source_version, updated_at)
VALUES ('$system_access_id'::uuid, '$E2E_ACCOUNT_ID'::uuid, '$system_id'::uuid,
  'LAVADO_ULTRASONICO', 'ACTIVE', 1, CURRENT_TIMESTAMP);

INSERT INTO student_academic_profile_read_model
  (student_academic_profile_id, person_id, account_id, student_number,
   current_semester, academic_status, source_version, updated_at)
VALUES ('$academic_profile_id'::uuid, '$person_id'::uuid, '$E2E_ACCOUNT_ID'::uuid,
  '$nrc', 1, 'ACTIVE', 1, CURRENT_TIMESTAMP);

INSERT INTO course_section_read_model
  (course_section_id, academic_period_id, nrc, name, status, source_version, updated_at)
VALUES ('$course_section_id'::uuid, '$period_id'::uuid, '$nrc',
  'E2E Course Section', 'ACTIVE', 1, CURRENT_TIMESTAMP);

INSERT INTO student_enrollment_read_model
  (student_academic_profile_id, account_id, course_section_id, academic_period_id,
   nrc, status, source_version, updated_at)
VALUES ('$academic_profile_id'::uuid, '$E2E_ACCOUNT_ID'::uuid, '$course_section_id'::uuid,
  '$period_id'::uuid, '$nrc', 'ACTIVE', 1, CURRENT_TIMESTAMP);

INSERT INTO appointment_time_slot_read_model
  (appointment_time_slot_id, calendar_id, source_schedule_id, service_date, starts_at, ends_at,
   timezone, booking_deadline_at, cancellation_deadline_at, arrival_tolerance_until, status,
   capacity_normal, capacity_journey, capacity_immunocompromised,
   slot_source_version, capacity_projection_version, updated_at)
VALUES ('$slot_id'::uuid, '$calendar_id'::uuid, '$schedule_id'::uuid, '$service_date'::date,
  '$starts_at'::timestamptz, '$ends_at'::timestamptz, 'America/Mexico_City',
  '$booking_deadline_at'::timestamptz, '$cancellation_deadline_at'::timestamptz,
  '$arrival_tolerance_until'::timestamptz, 'ACTIVE', 4, 0, 1, 1, 1, CURRENT_TIMESTAMP);
COMMIT;
SQL

  trap - EXIT INT TERM
  printf 'Synthetic wash-student preconditions are ready; runtime manifest: %s\n' "$manifest"
}

cleanup() {
  require_runtime
  [ -f "$manifest" ] || fail "fixture manifest does not exist"
  ACCOUNT_ID=$(manifest_value ACCOUNT_ID)
  LOGIN_IDENTIFIER=$(manifest_value LOGIN_IDENTIFIER)
  PERSON_ID=$(manifest_value PERSON_ID)
  ACADEMIC_PROFILE_ID=$(manifest_value ACADEMIC_PROFILE_ID)
  COURSE_SECTION_ID=$(manifest_value COURSE_SECTION_ID)
  SYSTEM_ACCESS_ID=$(manifest_value SYSTEM_ACCESS_ID)
  SYSTEM_ID=$(manifest_value SYSTEM_ID)
  PERIOD_ID=$(manifest_value PERIOD_ID)
  SLOT_ID=$(manifest_value SLOT_ID)
  SCHEDULE_ID=$(manifest_value SCHEDULE_ID)
  CALENDAR_ID=$(manifest_value CALENDAR_ID)
  RUN_KEY=$(manifest_value RUN_KEY)
  NRC=$(manifest_value NRC)
  BFF_ROLE_WAS_ABSENT=$(manifest_value BFF_ROLE_WAS_ABSENT)
  OWNER_ROLE_WAS_ABSENT=$(manifest_value OWNER_ROLE_WAS_ABSENT)
  require_uuid "$ACCOUNT_ID" ACCOUNT_ID
  require_uuid "$PERSON_ID" PERSON_ID
  require_uuid "$ACADEMIC_PROFILE_ID" ACADEMIC_PROFILE_ID
  require_uuid "$COURSE_SECTION_ID" COURSE_SECTION_ID
  require_uuid "$SYSTEM_ACCESS_ID" SYSTEM_ACCESS_ID
  require_uuid "$SYSTEM_ID" SYSTEM_ID
  require_uuid "$PERIOD_ID" PERIOD_ID
  require_uuid "$SLOT_ID" SLOT_ID
  require_uuid "$SCHEDULE_ID" SCHEDULE_ID
  require_uuid "$CALENDAR_ID" CALENDAR_ID
  require_fixture_login "$LOGIN_IDENTIFIER"
  case "$RUN_KEY" in e2e-wash-*) ;; *) fail "unsafe manifest run key" ;; esac
  case "$RUN_KEY" in *[!A-Za-z0-9-]*) fail "unsafe manifest run key" ;; esac
  case "$NRC" in E2E-*) ;; *) fail "unsafe manifest NRC" ;; esac
  case "$NRC" in *[!A-Za-z0-9-]*) fail "unsafe manifest NRC" ;; esac
  case "$BFF_ROLE_WAS_ABSENT:$OWNER_ROLE_WAS_ABSENT" in 0:0|0:1|1:0|1:1) ;; *) fail "unsafe manifest role flags" ;; esac

  identity_matches=$(sql "$bff_database" <<SQL
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM account_presentation_read_model
  WHERE account_id = '$ACCOUNT_ID'::uuid AND login_identifier = '$LOGIN_IDENTIFIER'
) THEN 'yes' ELSE 'no' END;
SQL
  )
  [ "$identity_matches" = yes ] || fail "cleanup refused: manifest no longer matches the synthetic account"

  attempts=0
  while :; do
    pending=$(sql "$bff_database" <<SQL
SELECT count(*) FROM client_operations
WHERE actor_account_id = '$ACCOUNT_ID'::uuid
  AND idempotency_key IN ('$RUN_KEY-schedule', '$RUN_KEY-cancel')
  AND status = 'PENDING'
UNION ALL
SELECT count(*) FROM appointment_availability_refresh
WHERE student_account_id = '$ACCOUNT_ID'::uuid
  AND course_section_id = '$COURSE_SECTION_ID'::uuid
  AND status IN ('PENDING', 'PUBLISHED');
SQL
    )
    owner_pending=$(sql "$appointments_database" <<SQL
SELECT count(*) FROM outbox_messages
WHERE (
    aggregate_id IN (SELECT appointment_id FROM appointments
      WHERE student_account_id = '$ACCOUNT_ID'::uuid AND appointment_time_slot_id = '$SLOT_ID'::uuid)
    OR payload #>> '{data,requestContext,studentAccountId}' = '$ACCOUNT_ID'
  ) AND status <> 'PUBLISHED';
SQL
    )
    pending=$(printf '%s\n' "$pending" | awk '{ total += $1 } END { print total + 0 }')
    [ "$pending" = 0 ] && [ "$owner_pending" = 0 ] && break
    attempts=$((attempts + 1))
    [ "$attempts" -lt 30 ] || fail "cleanup refused while a synthetic command or owner outbox message is still active"
    sleep 1
  done

  owner_state=$(sql "$appointments_database" <<SQL
SELECT status || '|' || version FROM appointments
WHERE student_account_id = '$ACCOUNT_ID'::uuid
  AND appointment_time_slot_id = '$SLOT_ID'::uuid;
SQL
  )
  if [ -n "$owner_state" ]; then
    attempts=0
    while :; do
      bff_state=$(sql "$bff_database" <<SQL
SELECT status || '|' || source_version FROM appointment_read_model
WHERE student_account_id = '$ACCOUNT_ID'::uuid
  AND appointment_time_slot_id = '$SLOT_ID'::uuid;
SQL
      )
      [ "$bff_state" = "$owner_state" ] && break
      attempts=$((attempts + 1))
      [ "$attempts" -lt 30 ] || fail "cleanup refused before the final appointment Event converged in the BFF"
      sleep 1
    done
  fi

  sql "$bff_database" >/dev/null <<SQL
BEGIN;
CREATE TEMP TABLE e2e_operation_ids ON COMMIT DROP AS
SELECT operation_id FROM client_operations
WHERE actor_account_id = '$ACCOUNT_ID'::uuid
  AND idempotency_key IN ('$RUN_KEY-schedule', '$RUN_KEY-cancel');
CREATE TEMP TABLE e2e_appointment_ids ON COMMIT DROP AS
SELECT appointment_id FROM appointment_read_model
WHERE student_account_id = '$ACCOUNT_ID'::uuid
  AND appointment_time_slot_id = '$SLOT_ID'::uuid;

DELETE FROM appointment_availability_refresh WHERE student_account_id = '$ACCOUNT_ID'::uuid
  AND course_section_id = '$COURSE_SECTION_ID'::uuid;
DELETE FROM appointment_availability_read_model WHERE student_account_id = '$ACCOUNT_ID'::uuid
  AND course_section_id = '$COURSE_SECTION_ID'::uuid;
DELETE FROM inbox_messages WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids)
  OR aggregate_id IN (SELECT appointment_id FROM e2e_appointment_ids);
DELETE FROM outbox_messages WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DELETE FROM idempotency_records WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DELETE FROM client_operations WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DELETE FROM appointment_read_model WHERE appointment_id IN (SELECT appointment_id FROM e2e_appointment_ids);
DELETE FROM appointment_time_slot_read_model WHERE appointment_time_slot_id = '$SLOT_ID'::uuid;
DELETE FROM student_enrollment_read_model
  WHERE student_academic_profile_id = '$ACADEMIC_PROFILE_ID'::uuid
    AND course_section_id = '$COURSE_SECTION_ID'::uuid;
DELETE FROM course_section_read_model WHERE course_section_id = '$COURSE_SECTION_ID'::uuid;
DELETE FROM student_academic_profile_read_model
  WHERE student_academic_profile_id = '$ACADEMIC_PROFILE_ID'::uuid
    AND account_id = '$ACCOUNT_ID'::uuid;
DELETE FROM authorization_access_projection
  WHERE system_access_id = '$SYSTEM_ACCESS_ID'::uuid AND account_id = '$ACCOUNT_ID'::uuid;
$(if [ "$BFF_ROLE_WAS_ABSENT" = 1 ]; then
    printf "DELETE FROM authorization_role_projection WHERE account_id = '%s'::uuid AND role_code = 'ALUMNO';" "$ACCOUNT_ID"
  else
    printf "UPDATE authorization_role_projection SET role_code = 'E2E_TESTER', updated_at = CURRENT_TIMESTAMP WHERE account_id = '%s'::uuid;" "$ACCOUNT_ID"
  fi)
COMMIT;
SQL

  sql "$appointments_database" >/dev/null <<SQL
BEGIN;
CREATE TEMP TABLE e2e_appointment_ids ON COMMIT DROP AS
SELECT appointment_id FROM appointments
WHERE student_account_id = '$ACCOUNT_ID'::uuid
  AND appointment_time_slot_id = '$SLOT_ID'::uuid;
CREATE TEMP TABLE e2e_inbound_message_ids ON COMMIT DROP AS
SELECT causation_id AS message_id FROM outbox_messages
WHERE aggregate_id IN (SELECT appointment_id FROM e2e_appointment_ids)
   OR payload #>> '{data,requestContext,studentAccountId}' = '$ACCOUNT_ID';

DELETE FROM missed_evaluation_confirmations WHERE appointment_id IN (SELECT appointment_id FROM e2e_appointment_ids);
DELETE FROM pending_capacity_evaluation WHERE appointment_id IN (SELECT appointment_id FROM e2e_appointment_ids);
DELETE FROM pending_wash_execution_events WHERE appointment_id IN (SELECT appointment_id FROM e2e_appointment_ids);
DELETE FROM wash_execution_transition_projection WHERE appointment_id IN (SELECT appointment_id FROM e2e_appointment_ids);
DELETE FROM wash_execution_evidence_projection WHERE appointment_id IN (SELECT appointment_id FROM e2e_appointment_ids);
DELETE FROM processed_messages WHERE message_id IN (SELECT message_id FROM e2e_inbound_message_ids WHERE message_id IS NOT NULL);
DELETE FROM outbox_messages
WHERE aggregate_id IN (SELECT appointment_id FROM e2e_appointment_ids)
   OR payload #>> '{data,requestContext,studentAccountId}' = '$ACCOUNT_ID';
DELETE FROM command_idempotency_records
WHERE idempotency_key IN ('$RUN_KEY-schedule', '$RUN_KEY-cancel');
DELETE FROM appointments WHERE appointment_id IN (SELECT appointment_id FROM e2e_appointment_ids);
DELETE FROM appointment_capacity_projections WHERE appointment_time_slot_id = '$SLOT_ID'::uuid;
DELETE FROM appointment_time_slots WHERE appointment_time_slot_id = '$SLOT_ID'::uuid;
DELETE FROM student_course_section_projection
  WHERE academic_profile_id = '$ACADEMIC_PROFILE_ID'::uuid
    AND course_section_id = '$COURSE_SECTION_ID'::uuid;
DELETE FROM course_section_projection WHERE course_section_id = '$COURSE_SECTION_ID'::uuid;
DELETE FROM student_academic_profile_projection
  WHERE academic_profile_id = '$ACADEMIC_PROFILE_ID'::uuid
    AND student_account_id = '$ACCOUNT_ID'::uuid;
DELETE FROM wash_system_access_projection
  WHERE system_access_id = '$SYSTEM_ACCESS_ID'::uuid AND account_id = '$ACCOUNT_ID'::uuid;
$(if [ "$OWNER_ROLE_WAS_ABSENT" = 1 ]; then
    printf "DELETE FROM account_role_projection WHERE account_id = '%s'::uuid AND role_code = 'ALUMNO';" "$ACCOUNT_ID"
  else
    printf "UPDATE account_role_projection SET role_code = 'E2E_TESTER', updated_at = CURRENT_TIMESTAMP WHERE account_id = '%s'::uuid;" "$ACCOUNT_ID"
  fi)
COMMIT;
SQL

  rm -f -- "$manifest"
  printf 'Synthetic wash-student fixture cleaned.\n'
}

case "$mode" in
  bootstrap) bootstrap ;;
  cleanup) cleanup ;;
  *) fail "usage: $0 bootstrap|cleanup" ;;
esac

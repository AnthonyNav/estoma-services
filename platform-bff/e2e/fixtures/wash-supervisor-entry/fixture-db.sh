#!/bin/sh
set -eu

mode=${1:-}
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
platform_dir=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
report_dir=${E2E_REPORT_DIR:-$platform_dir/target/e2e/wash-supervisor-entry}
manifest=${E2E_ENTRY_FIXTURE_MANIFEST:-$report_dir/entry-fixture.env}
student_manifest=${E2E_STUDENT_FIXTURE_MANIFEST:-$report_dir/student-fixture.env}
supervisor_manifest=${E2E_SUPERVISOR_FIXTURE_MANIFEST:-$report_dir/supervisor-fixture.env}
bff_database=${E2E_BFF_DATABASE:-platform_bff_db}
appointments_database=${E2E_APPOINTMENTS_DATABASE:-appointments_db}
execution_database=${E2E_WASH_EXECUTION_DATABASE:-wash_execution_db}

fail() {
  printf 'wash-supervisor-entry fixture: %s\n' "$*" >&2
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

manifest_value_from() {
  source_manifest=$1
  key=$2
  value=$(sed -n "s/^${key}=//p" "$source_manifest")
  [ -n "$value" ] || fail "$source_manifest is missing $key"
  [ "$(printf '%s\n' "$value" | wc -l)" -eq 1 ] || fail "$source_manifest repeats $key"
  printf '%s\n' "$value"
}

manifest_value() {
  manifest_value_from "$manifest" "$1"
}

student_value() {
  manifest_value_from "$student_manifest" "$1"
}

supervisor_value() {
  manifest_value_from "$supervisor_manifest" "$1"
}

wait_for_single_value() {
  description=$1
  database=$2
  statement=$3
  attempts=0
  while :; do
    value=$(printf '%s\n' "$statement" | sql "$database")
    [ -z "$value" ] || {
      [ "$(printf '%s\n' "$value" | wc -l)" -eq 1 ] || fail "$description returned more than one row"
      printf '%s\n' "$value"
      return
    }
    attempts=$((attempts + 1))
    [ "$attempts" -lt 30 ] || fail "timed out waiting for $description"
    sleep 1
  done
}

bootstrap() {
  require_runtime
  [ -f "$student_manifest" ] || fail "student fixture manifest does not exist"
  [ -f "$supervisor_manifest" ] || fail "supervisor fixture manifest does not exist"
  [ ! -e "$manifest" ] || fail "entry fixture manifest already exists; run cleanup first"

  student_account_id=$(student_value ACCOUNT_ID)
  slot_id=$(student_value SLOT_ID)
  calendar_id=$(student_value CALENDAR_ID)
  run_key=$(student_value RUN_KEY)
  supervisor_account_id=$(supervisor_value SUPERVISOR_ACCOUNT_ID)
  system_id=$(supervisor_value SYSTEM_ID)
  require_uuid "$student_account_id" student_ACCOUNT_ID
  require_uuid "$slot_id" student_SLOT_ID
  require_uuid "$calendar_id" student_CALENDAR_ID
  require_uuid "$supervisor_account_id" supervisor_ACCOUNT_ID
  require_uuid "$system_id" supervisor_SYSTEM_ID
  [ "$student_account_id" != "$supervisor_account_id" ] || fail "student and supervisor must be different accounts"
  case "$run_key" in e2e-wash-*) ;; *) fail "student manifest has an unsafe run key" ;; esac
  case "$run_key" in *[!A-Za-z0-9-]*) fail "student manifest has an unsafe run key" ;; esac

  register_key=${E2E_REGISTER_ARRIVAL_IDEMPOTENCY_KEY:-$run_key-register-arrival}
  decide_key=${E2E_DECIDE_ENTRY_IDEMPOTENCY_KEY:-$run_key-decide-entry}
  case "$register_key:$decide_key" in *[!A-Za-z0-9:_-]*) fail "unsafe entry idempotency key" ;; esac
  [ "$register_key" != "$decide_key" ] || fail "entry idempotency keys must differ"

  appointment_id=$(wait_for_single_value "synthetic scheduled appointment" "$appointments_database" "
SELECT appointment_id FROM appointments
WHERE student_account_id = '$student_account_id'::uuid
  AND appointment_time_slot_id = '$slot_id'::uuid
  AND status = 'SCHEDULED';")
  require_uuid "$appointment_id" APPOINTMENT_ID

  slot_values=$(sql "$appointments_database" <<SQL
SELECT service_date::text || '|' || starts_at::text || '|' || ends_at::text || '|' ||
       timezone || '|' || arrival_tolerance_until::text || '|' || version::text
FROM appointment_time_slots
WHERE appointment_time_slot_id = '$slot_id'::uuid
  AND calendar_id = '$calendar_id'::uuid
  AND status = 'ACTIVE';
SQL
  )
  [ -n "$slot_values" ] || fail "student slot is not active in Appointments"
  [ "$(printf '%s\n' "$slot_values" | wc -l)" -eq 1 ] || fail "student slot is not unique"
  prior_ifs=$IFS
  IFS='|'
  set -- $slot_values
  IFS=$prior_ifs
  [ "$#" -eq 6 ] || fail "could not capture the authoritative slot"
  service_date=$1
  starts_at=$2
  ends_at=$3
  timezone=$4
  arrival_tolerance_until=$5
  slot_version=$6
  case "$slot_version" in *[!0-9]*|'') fail "unsafe slot version" ;; esac
  [ "$timezone" = 'America/Mexico_City' ] || fail "unexpected synthetic slot timezone"

  original_role=$(sql "$execution_database" <<SQL
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM authorization_role_projection
  WHERE account_id = '$supervisor_account_id'::uuid
) THEN (
  SELECT role_code || '|' || role_source_version::text || '|' || extract(epoch FROM updated_at)::text
  FROM authorization_role_projection
  WHERE account_id = '$supervisor_account_id'::uuid
) ELSE '<absent>' END;
SQL
  )
  case "$original_role" in
    '<absent>')
      role_was_absent=1
      original_role_source_version=0
      original_role_updated_epoch=0
      ;;
    E2E_TESTER\|*)
      role_was_absent=0
      prior_ifs=$IFS
      IFS='|'
      set -- $original_role
      IFS=$prior_ifs
      [ "$#" -eq 3 ] || fail "could not capture the complete execution role"
      original_role_source_version=$2
      original_role_updated_epoch=$3
      case "$original_role_source_version" in *[!0-9]*|'') fail "unsafe original role version" ;; esac
      case "$original_role_updated_epoch" in *[!0-9.]*|'') fail "unsafe original role timestamp" ;; esac
      ;;
    *) fail "execution role is not absent or E2E_TESTER" ;;
  esac

  existing_access=$(sql "$execution_database" <<SQL
SELECT count(*) FROM authorization_access_projection
WHERE account_id = '$supervisor_account_id'::uuid
  AND system_code = 'LAVADO_ULTRASONICO';
SQL
  )
  [ "$existing_access" = 0 ] || fail "synthetic supervisor already has Wash Execution access"
  existing_slot=$(sql "$execution_database" <<SQL
SELECT count(*) FROM appointment_time_slot_projection
WHERE appointment_time_slot_id = '$slot_id'::uuid;
SQL
  )
  [ "$existing_slot" = 0 ] || fail "synthetic slot projection already exists in Wash Execution"
  readiness=$(sql "$execution_database" <<SQL
SELECT count(*) FROM projection_recovery_state
WHERE projection_name IN ('appointments','wash-administration','authorization')
  AND readiness_status = 'READY';
SQL
  )
  [ "$readiness" = 3 ] || fail "Wash Execution projections are not READY"

  projected_appointment=$(wait_for_single_value "Wash Execution appointment projection" "$execution_database" "
SELECT appointment_id FROM appointment_projection
WHERE appointment_id = '$appointment_id'::uuid
  AND student_account_id = '$student_account_id'::uuid
  AND appointment_time_slot_id = '$slot_id'::uuid
  AND appointment_status = 'SCHEDULED';")
  [ "$projected_appointment" = "$appointment_id" ] || fail "unexpected appointment projection"

  fixture_role_updated_epoch=$(sql "$execution_database" <<SQL
SELECT extract(epoch FROM clock_timestamp())::text;
SQL
  )
  case "$fixture_role_updated_epoch" in *[!0-9.]*|'') fail "unsafe fixture timestamp" ;; esac

  mkdir -p "$report_dir"
  chmod 700 "$report_dir"
  umask 077
  {
    printf 'SUPERVISOR_ACCOUNT_ID=%s\n' "$supervisor_account_id"
    printf 'STUDENT_ACCOUNT_ID=%s\n' "$student_account_id"
    printf 'APPOINTMENT_ID=%s\n' "$appointment_id"
    printf 'SLOT_ID=%s\n' "$slot_id"
    printf 'CALENDAR_ID=%s\n' "$calendar_id"
    printf 'SYSTEM_ID=%s\n' "$system_id"
    printf 'RUN_KEY=%s\n' "$run_key"
    printf 'REGISTER_KEY=%s\n' "$register_key"
    printf 'DECIDE_KEY=%s\n' "$decide_key"
    printf 'ROLE_WAS_ABSENT=%s\n' "$role_was_absent"
    printf 'ORIGINAL_ROLE_SOURCE_VERSION=%s\n' "$original_role_source_version"
    printf 'ORIGINAL_ROLE_UPDATED_EPOCH=%s\n' "$original_role_updated_epoch"
    printf 'FIXTURE_ROLE_UPDATED_EPOCH=%s\n' "$fixture_role_updated_epoch"
  } > "$manifest"

  cleanup_on_error() {
    trap - EXIT INT TERM
    "$0" cleanup >/dev/null 2>&1 || true
  }
  trap cleanup_on_error EXIT INT TERM

  sql "$execution_database" >/dev/null <<SQL
BEGIN;
LOCK TABLE authorization_role_projection IN SHARE ROW EXCLUSIVE MODE;
DO \$guard\$ BEGIN
  IF NOT (( $role_was_absent = 1 AND NOT EXISTS (
    SELECT 1 FROM authorization_role_projection WHERE account_id = '$supervisor_account_id'::uuid))
    OR ( $role_was_absent = 0 AND EXISTS (
    SELECT 1 FROM authorization_role_projection WHERE account_id = '$supervisor_account_id'::uuid
      AND role_code = 'E2E_TESTER' AND role_source_version = $original_role_source_version
      AND updated_at = to_timestamp($original_role_updated_epoch)))) THEN
    RAISE EXCEPTION 'synthetic owner role changed before bootstrap';
  END IF;
END \$guard\$;
INSERT INTO authorization_role_projection
  (account_id, role_code, role_source_version, updated_at)
VALUES ('$supervisor_account_id'::uuid, 'SUPERVISOR_LAVADO', 1, to_timestamp($fixture_role_updated_epoch))
ON CONFLICT (account_id) DO UPDATE
SET role_code = 'SUPERVISOR_LAVADO', updated_at = EXCLUDED.updated_at;

INSERT INTO authorization_access_projection
  (account_id, system_id, system_code, access_status, access_source_version, updated_at)
VALUES ('$supervisor_account_id'::uuid, '$system_id'::uuid,
  'LAVADO_ULTRASONICO', 'ACTIVE', 1, CURRENT_TIMESTAMP);

INSERT INTO appointment_time_slot_projection
  (appointment_time_slot_id, calendar_id, service_date, starts_at, ends_at, timezone,
   arrival_tolerance_until, status, source_version, updated_at)
VALUES ('$slot_id'::uuid, '$calendar_id'::uuid, '$service_date'::date,
  '$starts_at'::timestamptz, '$ends_at'::timestamptz, '$timezone',
  '$arrival_tolerance_until'::timestamptz, 'ACTIVE', $slot_version, CURRENT_TIMESTAMP);
COMMIT;
SQL

  trap - EXIT INT TERM
  printf 'Synthetic rejected-entry preconditions are ready; runtime manifest: %s\n' "$manifest"
}

load_manifest() {
  [ -f "$manifest" ] || fail "entry fixture manifest does not exist"
  SUPERVISOR_ACCOUNT_ID=$(manifest_value SUPERVISOR_ACCOUNT_ID)
  STUDENT_ACCOUNT_ID=$(manifest_value STUDENT_ACCOUNT_ID)
  APPOINTMENT_ID=$(manifest_value APPOINTMENT_ID)
  SLOT_ID=$(manifest_value SLOT_ID)
  CALENDAR_ID=$(manifest_value CALENDAR_ID)
  SYSTEM_ID=$(manifest_value SYSTEM_ID)
  RUN_KEY=$(manifest_value RUN_KEY)
  REGISTER_KEY=$(manifest_value REGISTER_KEY)
  DECIDE_KEY=$(manifest_value DECIDE_KEY)
  ROLE_WAS_ABSENT=$(manifest_value ROLE_WAS_ABSENT)
  ORIGINAL_ROLE_SOURCE_VERSION=$(manifest_value ORIGINAL_ROLE_SOURCE_VERSION)
  ORIGINAL_ROLE_UPDATED_EPOCH=$(manifest_value ORIGINAL_ROLE_UPDATED_EPOCH)
  FIXTURE_ROLE_UPDATED_EPOCH=$(manifest_value FIXTURE_ROLE_UPDATED_EPOCH)
  require_uuid "$SUPERVISOR_ACCOUNT_ID" SUPERVISOR_ACCOUNT_ID
  require_uuid "$STUDENT_ACCOUNT_ID" STUDENT_ACCOUNT_ID
  require_uuid "$APPOINTMENT_ID" APPOINTMENT_ID
  require_uuid "$SLOT_ID" SLOT_ID
  require_uuid "$CALENDAR_ID" CALENDAR_ID
  require_uuid "$SYSTEM_ID" SYSTEM_ID
  case "$RUN_KEY" in e2e-wash-*) ;; *) fail "unsafe manifest run key" ;; esac
  case "$RUN_KEY:$REGISTER_KEY:$DECIDE_KEY" in *[!A-Za-z0-9:_-]*) fail "unsafe manifest run keys" ;; esac
  case "$ROLE_WAS_ABSENT" in 0|1) ;; *) fail "unsafe original role flag" ;; esac
  case "$ORIGINAL_ROLE_SOURCE_VERSION" in *[!0-9]*|'') fail "unsafe original role version" ;; esac
  case "$ORIGINAL_ROLE_UPDATED_EPOCH" in *[!0-9.]*|'') fail "unsafe original role timestamp" ;; esac
  case "$FIXTURE_ROLE_UPDATED_EPOCH" in *[!0-9.]*|'') fail "unsafe fixture role timestamp" ;; esac
}

audit() {
  require_runtime
  [ -f "$student_manifest" ] || fail "student fixture must remain active during entry audit"
  [ -f "$supervisor_manifest" ] || fail "supervisor fixture must remain active during entry audit"
  load_manifest
  state=$(sql "$execution_database" <<SQL
SELECT status || '|' || version::text || '|' || CASE WHEN started_at IS NULL THEN 'no-start' ELSE 'started' END
FROM wash_executions WHERE appointment_id = '$APPOINTMENT_ID'::uuid;
SQL
  )
  [ "$state" = 'ENTRY_REJECTED|2|no-start' ] || fail "execution did not converge to ENTRY_REJECTED v2"
  validation=$(sql "$execution_database" <<SQL
SELECT decision || '|' || CASE WHEN rejection_reason IS NOT NULL THEN 'reason' ELSE 'no-reason' END
FROM entry_validations
WHERE wash_execution_id = (SELECT wash_execution_id FROM wash_executions
  WHERE appointment_id = '$APPOINTMENT_ID'::uuid);
SQL
  )
  [ "$validation" = 'REJECTED|reason' ] || fail "rejected entry validation is incomplete"
  assignments=$(sql "$execution_database" <<SQL
SELECT count(*) FROM resource_assignments
WHERE wash_execution_id = (SELECT wash_execution_id FROM wash_executions
  WHERE appointment_id = '$APPOINTMENT_ID'::uuid);
SQL
  )
  [ "$assignments" = 0 ] || fail "rejected entry unexpectedly allocated a resource"
  event_count=$(sql "$execution_database" <<SQL
SELECT count(*) FROM outbox_messages
WHERE aggregate_id = (SELECT wash_execution_id FROM wash_executions
    WHERE appointment_id = '$APPOINTMENT_ID'::uuid)
  AND message_kind = 'EVENT'
  AND ((message_type = 'WashArrivalRegistered' AND aggregate_version = 1)
    OR (message_type = 'WashEntryRejected' AND aggregate_version = 2))
  AND status = 'PUBLISHED';
SQL
  )
  [ "$event_count" = 2 ] || fail "arrival/rejection Events are not durably published"
  bff_state=$(sql "$bff_database" <<SQL
SELECT status || '|' || execution_version::text
FROM wash_execution_read_model
WHERE appointment_id = '$APPOINTMENT_ID'::uuid;
SQL
  )
  [ "$bff_state" = 'ENTRY_REJECTED|2' ] || fail "BFF execution projection did not converge"
  bff_appointment_state=$(sql "$bff_database" <<SQL
SELECT status || '|' || source_version::text FROM appointment_read_model
WHERE appointment_id = '$APPOINTMENT_ID'::uuid;
SQL
  )
  [ "$bff_appointment_state" = 'ENTRY_REJECTED|2' ] || fail "BFF appointment projection did not converge"
  owner_state=$(sql "$appointments_database" <<SQL
SELECT status || '|' || version::text FROM appointments
WHERE appointment_id = '$APPOINTMENT_ID'::uuid;
SQL
  )
  [ "$owner_state" = 'ENTRY_REJECTED|2' ] || fail "Appointments did not converge to ENTRY_REJECTED v2"
  audit_execution_id=$(sql "$execution_database" <<SQL
SELECT wash_execution_id FROM wash_executions WHERE appointment_id = '$APPOINTMENT_ID'::uuid;
SQL
  )
  require_uuid "$audit_execution_id" audit_WASH_EXECUTION_ID
  audit_arrival_id=$(sql "$execution_database" <<SQL
SELECT message_id FROM outbox_messages WHERE aggregate_id = '$audit_execution_id'::uuid
  AND message_type = 'WashArrivalRegistered' AND message_kind = 'EVENT';
SQL
  )
  require_uuid "$audit_arrival_id" audit_ARRIVAL_MESSAGE_ID
  audit_rejection_id=$(sql "$execution_database" <<SQL
SELECT message_id FROM outbox_messages WHERE aggregate_id = '$audit_execution_id'::uuid
  AND message_type = 'WashEntryRejected' AND message_kind = 'EVENT';
SQL
  )
  require_uuid "$audit_rejection_id" audit_REJECTION_MESSAGE_ID
  umask 077
  cp "$manifest" "$report_dir/entry-audit.env"
  {
    printf 'WASH_EXECUTION_ID=%s\n' "$audit_execution_id"
    printf 'ARRIVAL_MESSAGE_ID=%s\n' "$audit_arrival_id"
    printf 'REJECTION_MESSAGE_ID=%s\n' "$audit_rejection_id"
  } >> "$report_dir/entry-audit.env"
  printf 'Rejected supervisor entry audit passed.\n'
}

cleanup() {
  require_runtime
  [ -f "$student_manifest" ] || fail "entry cleanup must run before student cleanup"
  [ -f "$supervisor_manifest" ] || fail "entry cleanup must run before supervisor cleanup"
  load_manifest

  attempts=0
  while :; do
    pending_bff=$(sql "$bff_database" <<SQL
SELECT count(*) FROM client_operations
WHERE actor_account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid
  AND idempotency_key IN ('$REGISTER_KEY', '$DECIDE_KEY')
  AND status = 'PENDING';
SQL
    )
    pending_execution=$(sql "$execution_database" <<SQL
SELECT count(*) FROM outbox_messages
WHERE (idempotency_key IN ('$REGISTER_KEY', '$DECIDE_KEY')
    OR aggregate_id IN (SELECT wash_execution_id FROM wash_executions
      WHERE appointment_id = '$APPOINTMENT_ID'::uuid))
  AND status <> 'PUBLISHED'
UNION ALL
SELECT count(*) FROM processed_messages
WHERE idempotency_key IN ('$REGISTER_KEY', '$DECIDE_KEY') AND status = 'DEFERRED';
SQL
    )
    pending_execution=$(printf '%s\n' "$pending_execution" | awk '{ total += $1 } END { print total + 0 }')
    pending_appointments=$(sql "$appointments_database" <<SQL
SELECT count(*) FROM outbox_messages
WHERE aggregate_id = '$APPOINTMENT_ID'::uuid AND status <> 'PUBLISHED'
UNION ALL
SELECT count(*) FROM pending_wash_execution_events
WHERE appointment_id = '$APPOINTMENT_ID'::uuid AND status IN ('PENDING','PROCESSING');
SQL
    )
    pending_appointments=$(printf '%s\n' "$pending_appointments" | awk '{ total += $1 } END { print total + 0 }')
    [ "$pending_bff" = 0 ] && [ "$pending_execution" = 0 ] && [ "$pending_appointments" = 0 ] && break
    attempts=$((attempts + 1))
    [ "$attempts" -lt 30 ] || fail "cleanup refused while entry messages are active"
    sleep 1
  done

  execution_id=$(sql "$execution_database" <<SQL
SELECT wash_execution_id FROM wash_executions WHERE appointment_id = '$APPOINTMENT_ID'::uuid;
SQL
  )
  if [ -n "$execution_id" ]; then
    require_uuid "$execution_id" WASH_EXECUTION_ID
    assignments=$(sql "$execution_database" <<SQL
SELECT count(*) FROM resource_assignments
WHERE wash_execution_id = '$execution_id'::uuid;
SQL
    )
    [ "$assignments" = 0 ] || fail "cleanup refused: rejected fixture has resource assignments"
    final_state=$(sql "$execution_database" <<SQL
SELECT status || '|' || version::text FROM wash_executions
WHERE wash_execution_id = '$execution_id'::uuid;
SQL
    )
    case "$final_state" in
      'ENTRY_REJECTED|2') expected_execution='ENTRY_REJECTED|2'; expected_appointment='ENTRY_REJECTED|2' ;;
      'PENDING_ENTRY|1')
        pending_owned=$(sql "$execution_database" <<SQL
SELECT count(*) FROM wash_executions e
WHERE e.wash_execution_id = '$execution_id'::uuid
  AND e.started_at IS NULL
  AND EXISTS (SELECT 1 FROM appointment_projection p WHERE p.appointment_id = e.appointment_id
    AND p.student_account_id = '$STUDENT_ACCOUNT_ID'::uuid
    AND p.appointment_time_slot_id = '$SLOT_ID'::uuid)
  AND NOT EXISTS (SELECT 1 FROM entry_validations v WHERE v.wash_execution_id = e.wash_execution_id)
  AND EXISTS (SELECT 1 FROM outbox_messages m WHERE m.aggregate_id = e.wash_execution_id
    AND m.message_type = 'WashArrivalRegistered' AND m.aggregate_version = 1
    AND m.idempotency_key = '$REGISTER_KEY' AND m.status = 'PUBLISHED');
SQL
        )
        [ "$pending_owned" = 1 ] || fail "pending execution is not an untouched arrival owned by this run"
        expected_execution='PENDING_ENTRY|1'; expected_appointment='SCHEDULED|1'
        ;;
      *) fail "cleanup refused for execution outside the isolated rejection path" ;;
    esac
    attempts=0
    while :; do
      bff_state=$(sql "$bff_database" <<SQL
SELECT status || '|' || execution_version::text FROM wash_execution_read_model
WHERE wash_execution_id = '$execution_id'::uuid;
SQL
      )
      owner_state=$(sql "$appointments_database" <<SQL
SELECT status || '|' || version::text FROM appointments
WHERE appointment_id = '$APPOINTMENT_ID'::uuid;
SQL
      )
      bff_appointment_state=$(sql "$bff_database" <<SQL
SELECT status || '|' || source_version::text FROM appointment_read_model
WHERE appointment_id = '$APPOINTMENT_ID'::uuid;
SQL
      )
      arrival_consumed=$(sql "$appointments_database" <<SQL
SELECT count(*) FROM wash_execution_evidence_projection e
JOIN processed_messages m ON m.message_id = e.arrival_source_event_id
WHERE e.appointment_id = '$APPOINTMENT_ID'::uuid
  AND e.wash_execution_id = '$execution_id'::uuid
  AND m.message_type = 'WashArrivalRegistered' AND m.status = 'APPLIED';
SQL
      )
      [ "$bff_state" = "$expected_execution" ] && [ "$owner_state" = "$expected_appointment" ] \
        && [ "$bff_appointment_state" = "$expected_appointment" ] && [ "$arrival_consumed" = 1 ] && break
      attempts=$((attempts + 1))
      [ "$attempts" -lt 30 ] || fail "cleanup refused before rejection converged"
      sleep 1
    done
  fi

  owner_fixture_state=$(sql "$execution_database" <<SQL
SELECT COALESCE((SELECT role_code FROM authorization_role_projection
    WHERE account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid), '<absent>') || '|' ||
  (SELECT count(*) FROM authorization_access_projection
    WHERE account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid
      AND system_id = '$SYSTEM_ID'::uuid AND system_code = 'LAVADO_ULTRASONICO'
      AND access_status = 'ACTIVE' AND access_source_version = 1) || '|' ||
  (SELECT count(*) FROM appointment_time_slot_projection
    WHERE appointment_time_slot_id = '$SLOT_ID'::uuid AND calendar_id = '$CALENDAR_ID'::uuid);
SQL
  )
  case "$owner_fixture_state" in
    'SUPERVISOR_LAVADO|1|1') ;;
    '<absent>|0|0') [ "$ROLE_WAS_ABSENT" = 1 ] || fail "owner fixture state changed" ;;
    'E2E_TESTER|0|0') [ "$ROLE_WAS_ABSENT" = 0 ] || fail "owner fixture state changed" ;;
    *) fail "owner fixture state no longer matches its manifest" ;;
  esac

  sql "$execution_database" >/dev/null <<SQL
BEGIN;
LOCK TABLE authorization_role_projection IN SHARE ROW EXCLUSIVE MODE;
DO \$guard\$ BEGIN
  IF NOT (( $ROLE_WAS_ABSENT = 1 AND NOT EXISTS (
    SELECT 1 FROM authorization_role_projection WHERE account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid))
    OR EXISTS (SELECT 1 FROM authorization_role_projection
      WHERE account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid AND (
        (role_code = 'SUPERVISOR_LAVADO'
          AND role_source_version = CASE WHEN $ROLE_WAS_ABSENT = 1 THEN 1 ELSE $ORIGINAL_ROLE_SOURCE_VERSION END
          AND updated_at = to_timestamp($FIXTURE_ROLE_UPDATED_EPOCH))
        OR ($ROLE_WAS_ABSENT = 0 AND role_code = 'E2E_TESTER'
          AND role_source_version = $ORIGINAL_ROLE_SOURCE_VERSION
          AND updated_at = to_timestamp($ORIGINAL_ROLE_UPDATED_EPOCH))))) THEN
    RAISE EXCEPTION 'synthetic owner role changed before cleanup';
  END IF;
END \$guard\$;
CREATE TEMP TABLE e2e_execution_ids ON COMMIT DROP AS
SELECT wash_execution_id FROM wash_executions WHERE appointment_id = '$APPOINTMENT_ID'::uuid;
DELETE FROM entry_validations
WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM outbox_messages
WHERE idempotency_key IN ('$REGISTER_KEY', '$DECIDE_KEY')
   OR aggregate_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM processed_messages
WHERE idempotency_key IN ('$REGISTER_KEY', '$DECIDE_KEY');
DELETE FROM processed_messages
WHERE aggregate_id = '$APPOINTMENT_ID'::uuid
  AND message_type = 'AppointmentScheduled';
DELETE FROM command_idempotency_records
WHERE producer = 'bff' AND idempotency_key IN ('$REGISTER_KEY', '$DECIDE_KEY');
DELETE FROM wash_executions
WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM appointment_projection
WHERE appointment_id = '$APPOINTMENT_ID'::uuid
  AND student_account_id = '$STUDENT_ACCOUNT_ID'::uuid
  AND appointment_time_slot_id = '$SLOT_ID'::uuid;
DELETE FROM appointment_time_slot_projection
WHERE appointment_time_slot_id = '$SLOT_ID'::uuid AND calendar_id = '$CALENDAR_ID'::uuid;
DELETE FROM authorization_access_projection
WHERE account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid
  AND system_id = '$SYSTEM_ID'::uuid
  AND system_code = 'LAVADO_ULTRASONICO'
  AND access_status = 'ACTIVE' AND access_source_version = 1;
$(if [ "$ROLE_WAS_ABSENT" = 1 ]; then
    printf "DELETE FROM authorization_role_projection WHERE account_id = '%s'::uuid AND role_code = 'SUPERVISOR_LAVADO';" "$SUPERVISOR_ACCOUNT_ID"
  else
    printf "UPDATE authorization_role_projection SET role_code = 'E2E_TESTER', role_source_version = %s, updated_at = to_timestamp(%s) WHERE account_id = '%s'::uuid AND role_code = 'SUPERVISOR_LAVADO';" "$ORIGINAL_ROLE_SOURCE_VERSION" "$ORIGINAL_ROLE_UPDATED_EPOCH" "$SUPERVISOR_ACCOUNT_ID"
  fi)
COMMIT;
SQL

  if [ "${E2E_TEST_FAIL_AFTER_OWNER_CLEANUP:-0}" = 1 ]; then
    fail "injected failure after Wash Execution cleanup"
  fi

  sql "$bff_database" >/dev/null <<SQL
BEGIN;
CREATE TEMP TABLE e2e_operation_ids ON COMMIT DROP AS
SELECT operation_id FROM client_operations
WHERE actor_account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid
  AND idempotency_key IN ('$REGISTER_KEY', '$DECIDE_KEY');
CREATE TEMP TABLE e2e_execution_ids ON COMMIT DROP AS
SELECT wash_execution_id FROM wash_execution_read_model
WHERE appointment_id = '$APPOINTMENT_ID'::uuid;
CREATE TEMP TABLE e2e_message_ids ON COMMIT DROP AS
SELECT message_id FROM outbox_messages
WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DELETE FROM inbox_messages
WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids)
   OR aggregate_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM quarantined_messages
WHERE message_id IN (SELECT message_id FROM e2e_message_ids);
DELETE FROM wash_execution_read_model
WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM outbox_messages WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DELETE FROM idempotency_records WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DELETE FROM client_operations WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
COMMIT;
SQL

  rm -f -- "$manifest"
  printf 'Synthetic rejected-entry fixture cleaned.\n'
}

case "$mode" in
  bootstrap) bootstrap ;;
  audit) audit ;;
  cleanup) cleanup ;;
  *) fail "usage: $0 bootstrap|audit|cleanup" ;;
esac

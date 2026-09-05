#!/bin/sh
set -eu

mode=${1:-}
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
platform_dir=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
report_dir=${E2E_REPORT_DIR:-$platform_dir/target/e2e/wash-supervisor-authorized}
manifest=${E2E_ENTRY_FIXTURE_MANIFEST:-$report_dir/entry-fixture.env}
student_manifest=${E2E_STUDENT_FIXTURE_MANIFEST:-$report_dir/student-fixture.env}
supervisor_manifest=${E2E_SUPERVISOR_FIXTURE_MANIFEST:-$report_dir/supervisor-fixture.env}
base_fixture="$script_dir/../wash-supervisor-entry/fixture-db.sh"
bff_database=${E2E_BFF_DATABASE:-platform_bff_db}
appointments_database=${E2E_APPOINTMENTS_DATABASE:-appointments_db}
execution_database=${E2E_WASH_EXECUTION_DATABASE:-wash_execution_db}

fail() { printf 'wash-supervisor-authorized fixture: %s\n' "$*" >&2; exit 1; }

require_runtime() {
  : "${E2E_PSQL_EXEC:?E2E_PSQL_EXEC must name an executable that accepts a database and reads SQL on stdin}"
  [ -x "$E2E_PSQL_EXEC" ] || fail "E2E_PSQL_EXEC is not executable"
  [ "${E2E_ISOLATED_NAMESPACE:-}" = wash-e2e ] || fail "E2E_ISOLATED_NAMESPACE must be wash-e2e"
  "$E2E_PSQL_EXEC" --assert-isolated-wash >/dev/null \
    || fail "database executor did not prove the isolated wash environment"
}

sql() { database=$1; "$E2E_PSQL_EXEC" "$database"; }

require_uuid() {
  value=$1 name=$2
  case "$value" in
    *[!0-9A-Fa-f-]*) fail "$name must be a UUID" ;;
    ????????-????-????-????-????????????) ;;
    *) fail "$name must be a UUID" ;;
  esac
}

manifest_value() {
  key=$1
  value=$(sed -n "s/^${key}=//p" "$manifest")
  [ -n "$value" ] || fail "$manifest is missing $key"
  [ "$(printf '%s\n' "$value" | wc -l)" -eq 1 ] || fail "$manifest repeats $key"
  printf '%s\n' "$value"
}

new_uuid() { sed -n '1p' /proc/sys/kernel/random/uuid; }

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
  CABIN_ID=$(manifest_value CABIN_ID)
  TANK_ID=$(manifest_value TANK_ID)
  CABIN_CODE=$(manifest_value CABIN_CODE)
  TANK_CODE=$(manifest_value TANK_CODE)
  CABIN_NAME=$(manifest_value CABIN_NAME)
  TANK_NAME=$(manifest_value TANK_NAME)
  for pair in \
    "$SUPERVISOR_ACCOUNT_ID:SUPERVISOR_ACCOUNT_ID" "$STUDENT_ACCOUNT_ID:STUDENT_ACCOUNT_ID" \
    "$APPOINTMENT_ID:APPOINTMENT_ID" "$SLOT_ID:SLOT_ID" "$CALENDAR_ID:CALENDAR_ID" \
    "$SYSTEM_ID:SYSTEM_ID" "$CABIN_ID:CABIN_ID" "$TANK_ID:TANK_ID"; do
    require_uuid "${pair%%:*}" "${pair#*:}"
  done
  case "$RUN_KEY" in e2e-wash-*) ;; *) fail "unsafe manifest run key" ;; esac
  case "$RUN_KEY:$REGISTER_KEY:$DECIDE_KEY:$CABIN_CODE:$TANK_CODE" in
    *[!A-Za-z0-9:_-]*) fail "unsafe manifest key or resource code" ;;
  esac
  [ "$CABIN_NAME" = "$CABIN_CODE" ] || fail "unsafe cabin name"
  [ "$TANK_NAME" = "$TANK_CODE" ] || fail "unsafe tank name"
  case "$ROLE_WAS_ABSENT" in 0|1) ;; *) fail "unsafe original role flag" ;; esac
  case "$ORIGINAL_ROLE_SOURCE_VERSION:$ORIGINAL_ROLE_UPDATED_EPOCH:$FIXTURE_ROLE_UPDATED_EPOCH" in
    *[!0-9.:]*) fail "unsafe role snapshot" ;;
  esac
}

bootstrap() {
  require_runtime
  [ ! -e "$manifest" ] || fail "entry fixture manifest already exists; run cleanup first"
  E2E_ENTRY_FIXTURE_MANIFEST="$manifest" E2E_REPORT_DIR="$report_dir" \
    E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" \
    E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" \
    "$base_fixture" bootstrap

  cabin_id=$(new_uuid); tank_id=$(new_uuid)
  require_uuid "$cabin_id" CABIN_ID; require_uuid "$tank_id" TANK_ID
  run_key=$(manifest_value RUN_KEY)
  cabin_code="E2E-${run_key#e2e-wash-}-CABIN"
  tank_code="E2E-${run_key#e2e-wash-}-TANK"
  case "$cabin_code:$tank_code" in *[!A-Za-z0-9:_-]*) fail "unsafe generated resource code" ;; esac
  {
    printf 'CABIN_ID=%s\n' "$cabin_id"
    printf 'TANK_ID=%s\n' "$tank_id"
    printf 'CABIN_CODE=%s\n' "$cabin_code"
    printf 'TANK_CODE=%s\n' "$tank_code"
    printf 'CABIN_NAME=%s\n' "$cabin_code"
    printf 'TANK_NAME=%s\n' "$tank_code"
  } >> "$manifest"

  cleanup_on_error() { trap - EXIT INT TERM; "$0" cleanup >/dev/null 2>&1 || true; }
  trap cleanup_on_error EXIT INT TERM
  sql "$execution_database" >/dev/null <<SQL
BEGIN;
DO \$guard\$ BEGIN
  IF EXISTS (SELECT 1 FROM cabin_projection WHERE cabin_id = '$cabin_id'::uuid OR code = '$cabin_code')
    OR EXISTS (SELECT 1 FROM tank_projection WHERE tank_id = '$tank_id'::uuid OR code = '$tank_code') THEN
    RAISE EXCEPTION 'synthetic resource already exists';
  END IF;
  IF EXISTS (SELECT 1 FROM cabin_projection c JOIN tank_projection t ON t.cabin_id=c.cabin_id
    WHERE c.status='ACTIVE' AND t.status='ACTIVE' AND c.cabin_type IN ('NORMAL','JOURNEY')) THEN
    RAISE EXCEPTION 'isolated execution database already has a compatible active resource';
  END IF;
END \$guard\$;
INSERT INTO cabin_projection(cabin_id,code,name,cabin_type,status,source_version,updated_at)
VALUES('$cabin_id'::uuid,'$cabin_code','$cabin_code','NORMAL','ACTIVE',1,CURRENT_TIMESTAMP);
INSERT INTO tank_projection(tank_id,cabin_id,code,name,configured_capacity,status,source_version,updated_at)
VALUES('$tank_id'::uuid,'$cabin_id'::uuid,'$tank_code','$tank_code',1,'ACTIVE',1,CURRENT_TIMESTAMP);
COMMIT;
SQL
  sql "$bff_database" >/dev/null <<SQL
BEGIN;
DO \$guard\$ BEGIN
  IF EXISTS (SELECT 1 FROM wash_cabin_read_model WHERE cabin_id='$cabin_id'::uuid OR code='$cabin_code')
    OR EXISTS (SELECT 1 FROM wash_tank_read_model WHERE tank_id='$tank_id'::uuid OR code='$tank_code') THEN
    RAISE EXCEPTION 'synthetic BFF resource already exists';
  END IF;
END \$guard\$;
INSERT INTO wash_cabin_read_model(cabin_id,code,name,cabin_type,status,source_version,updated_at)
VALUES('$cabin_id'::uuid,'$cabin_code','$cabin_code','NORMAL','ACTIVE',1,CURRENT_TIMESTAMP);
INSERT INTO wash_tank_read_model(tank_id,cabin_id,code,name,configured_capacity,status,source_version,updated_at)
VALUES('$tank_id'::uuid,'$cabin_id'::uuid,'$tank_code','$tank_code',1,'ACTIVE',1,CURRENT_TIMESTAMP);
COMMIT;
SQL
  trap - EXIT INT TERM
  printf 'Synthetic authorized-entry preconditions are ready; runtime manifest: %s\n' "$manifest"
}

audit() {
  require_runtime; load_manifest
  state=$(sql "$execution_database" <<SQL
SELECT status || '|' || version || '|' || CASE WHEN started_at IS NULL THEN 'no-start' ELSE 'started' END
FROM wash_executions WHERE appointment_id='$APPOINTMENT_ID'::uuid;
SQL
  )
  [ "$state" = 'IN_PROGRESS|2|started' ] || fail "execution did not converge to IN_PROGRESS v2"
  execution_id=$(sql "$execution_database" <<SQL
SELECT wash_execution_id FROM wash_executions WHERE appointment_id='$APPOINTMENT_ID'::uuid;
SQL
  ); require_uuid "$execution_id" WASH_EXECUTION_ID
  assignment=$(sql "$execution_database" <<SQL
SELECT resource_assignment_id::text || '|' || appointment_time_slot_id || '|' || cabin_id || '|' || tank_id ||
  '|' || assignment_type || '|' || CASE WHEN assigned_by_account_id IS NULL THEN 'automatic' ELSE 'manual' END ||
  '|' || CASE WHEN released_at IS NULL THEN 'active' ELSE 'released' END
FROM resource_assignments WHERE wash_execution_id='$execution_id'::uuid;
SQL
  )
  old_ifs=$IFS; IFS='|'; set -- $assignment; IFS=$old_ifs
  [ "$#" -eq 7 ] || fail "authorized execution does not have exactly one assignment"
  resource_assignment_id=$1
  require_uuid "$resource_assignment_id" RESOURCE_ASSIGNMENT_ID
  [ "$2|$3|$4|$5|$6|$7" = "$SLOT_ID|$CABIN_ID|$TANK_ID|INITIAL|automatic|active" ] \
    || fail "authorized assignment does not match the fixture"
  validation=$(sql "$execution_database" <<SQL
SELECT decision || '|' || identity_confirmed || '|' || requirements_satisfied || '|' ||
  CASE WHEN rejection_reason IS NULL THEN 'no-reason' ELSE 'reason' END
FROM entry_validations WHERE wash_execution_id='$execution_id'::uuid;
SQL
  )
  [ "$validation" = 'AUTHORIZED|true|true|no-reason' ] || fail "authorized validation is incomplete"
  event_count=$(sql "$execution_database" <<SQL
SELECT count(*) FROM outbox_messages WHERE aggregate_id='$execution_id'::uuid AND message_kind='EVENT'
 AND ((message_type='WashArrivalRegistered' AND aggregate_version=1)
   OR (message_type IN ('WashEntryAuthorized','WashResourceAssigned','WashExecutionStarted') AND aggregate_version=2))
 AND status='PUBLISHED';
SQL
  )
  [ "$event_count" = 4 ] || fail "authorized owner Events are not durably published"
  bff_execution=$(sql "$bff_database" <<SQL
SELECT status || '|' || execution_version FROM wash_execution_read_model WHERE wash_execution_id='$execution_id'::uuid;
SQL
  )
  [ "$bff_execution" = 'IN_PROGRESS|2' ] || fail "BFF execution projection did not converge"
  bff_assignment=$(sql "$bff_database" <<SQL
SELECT resource_assignment_id::text || '|' || appointment_time_slot_id || '|' || cabin_id || '|' || tank_id ||
  '|' || assignment_type || '|' || CASE WHEN released_at IS NULL THEN 'active' ELSE 'released' END ||
  '|' || source_version
FROM wash_resource_assignment_read_model WHERE wash_execution_id='$execution_id'::uuid;
SQL
  )
  [ "$bff_assignment" = "$resource_assignment_id|$SLOT_ID|$CABIN_ID|$TANK_ID|INITIAL|active|2" ] \
    || fail "BFF assignment projection did not converge"
  bff_resources=$(sql "$bff_database" <<SQL
SELECT (SELECT count(*) FROM wash_cabin_read_model WHERE cabin_id='$CABIN_ID'::uuid AND code='$CABIN_CODE'
 AND name='$CABIN_NAME' AND cabin_type='NORMAL' AND status='ACTIVE' AND source_version=1) || '|' ||
 (SELECT count(*) FROM wash_tank_read_model WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid
 AND code='$TANK_CODE' AND name='$TANK_NAME' AND configured_capacity=1 AND status='ACTIVE' AND source_version=1);
SQL
  )
  [ "$bff_resources" = '1|1' ] || fail "BFF resource presentation did not match the fixture"
  [ "$(sql "$appointments_database" <<SQL
SELECT status || '|' || version || '|' || CASE WHEN entry_authorized_at IS NULL THEN 'no-auth' ELSE 'authorized' END ||
  '|' || CASE WHEN started_at IS NULL THEN 'no-start' ELSE 'started' END
FROM appointments WHERE appointment_id='$APPOINTMENT_ID'::uuid;
SQL
  )" = 'IN_PROGRESS|3|authorized|started' ] || fail "Appointments did not converge to IN_PROGRESS v3"
  [ "$(sql "$bff_database" <<SQL
SELECT status || '|' || source_version FROM appointment_read_model WHERE appointment_id='$APPOINTMENT_ID'::uuid;
SQL
  )" = 'IN_PROGRESS|3' ] || fail "BFF appointment projection did not converge"
  printf 'Authorized supervisor entry audit passed.\n'
}

cleanup() {
  require_runtime
  [ -f "$student_manifest" ] || fail "entry cleanup must run before student cleanup"
  [ -f "$supervisor_manifest" ] || fail "entry cleanup must run before supervisor cleanup"
  load_manifest

  attempts=0
  while :; do
    pending_bff=$(sql "$bff_database" <<SQL
SELECT count(*) FROM client_operations WHERE actor_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
 AND idempotency_key IN ('$REGISTER_KEY','$DECIDE_KEY') AND status='PENDING';
SQL
    )
    pending_execution=$(sql "$execution_database" <<SQL
SELECT count(*) FROM outbox_messages WHERE (idempotency_key IN ('$REGISTER_KEY','$DECIDE_KEY')
 OR aggregate_id IN (SELECT wash_execution_id FROM wash_executions WHERE appointment_id='$APPOINTMENT_ID'::uuid))
 AND status <> 'PUBLISHED'
UNION ALL SELECT count(*) FROM processed_messages
 WHERE idempotency_key IN ('$REGISTER_KEY','$DECIDE_KEY') AND status='DEFERRED';
SQL
    ); pending_execution=$(printf '%s\n' "$pending_execution" | awk '{n+=$1} END {print n+0}')
    pending_appointments=$(sql "$appointments_database" <<SQL
SELECT count(*) FROM outbox_messages WHERE aggregate_id='$APPOINTMENT_ID'::uuid AND status <> 'PUBLISHED'
UNION ALL SELECT count(*) FROM pending_wash_execution_events
 WHERE appointment_id='$APPOINTMENT_ID'::uuid AND status IN ('PENDING','PROCESSING');
SQL
    ); pending_appointments=$(printf '%s\n' "$pending_appointments" | awk '{n+=$1} END {print n+0}')
    [ "$pending_bff" = 0 ] && [ "$pending_execution" = 0 ] && [ "$pending_appointments" = 0 ] && break
    attempts=$((attempts+1)); [ "$attempts" -lt 30 ] || fail "cleanup refused while entry messages are active"; sleep 1
  done

  execution_id=$(sql "$execution_database" <<SQL
SELECT wash_execution_id FROM wash_executions WHERE appointment_id='$APPOINTMENT_ID'::uuid;
SQL
  )
  if [ -n "$execution_id" ]; then
    require_uuid "$execution_id" WASH_EXECUTION_ID
    final_state=$(sql "$execution_database" <<SQL
SELECT status || '|' || version FROM wash_executions WHERE wash_execution_id='$execution_id'::uuid;
SQL
    )
    case "$final_state" in
      'IN_PROGRESS|2')
        proof=$(sql "$execution_database" <<SQL
SELECT count(*) FROM wash_executions e
JOIN entry_validations v ON v.wash_execution_id=e.wash_execution_id
JOIN resource_assignments a ON a.wash_execution_id=e.wash_execution_id
WHERE e.wash_execution_id='$execution_id'::uuid AND e.started_at IS NOT NULL
 AND v.decision='AUTHORIZED' AND v.identity_confirmed AND v.requirements_satisfied AND v.rejection_reason IS NULL
 AND v.decided_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
 AND a.appointment_time_slot_id='$SLOT_ID'::uuid AND a.cabin_id='$CABIN_ID'::uuid AND a.tank_id='$TANK_ID'::uuid
 AND a.assignment_type='INITIAL' AND a.assigned_by_account_id IS NULL AND a.released_at IS NULL
 AND (SELECT count(*) FROM resource_assignments x WHERE x.wash_execution_id=e.wash_execution_id)=1
 AND EXISTS (SELECT 1 FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
   AND m.message_type='WashArrivalRegistered' AND m.aggregate_version=1 AND m.idempotency_key='$REGISTER_KEY' AND m.status='PUBLISHED')
 AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id AND m.aggregate_version=2
   AND m.message_type IN ('WashEntryAuthorized','WashResourceAssigned','WashExecutionStarted') AND m.status='PUBLISHED')=3;
SQL
        )
        [ "$proof" = 1 ] || fail "cleanup refused: IN_PROGRESS execution is not the exact authorized fixture"
        expected_execution='IN_PROGRESS|2'; expected_appointment='IN_PROGRESS|3'
        ;;
      'PENDING_ENTRY|1')
        proof=$(sql "$execution_database" <<SQL
SELECT count(*) FROM wash_executions e WHERE e.wash_execution_id='$execution_id'::uuid AND e.started_at IS NULL
 AND EXISTS (SELECT 1 FROM appointment_projection p WHERE p.appointment_id=e.appointment_id
  AND p.student_account_id='$STUDENT_ACCOUNT_ID'::uuid AND p.appointment_time_slot_id='$SLOT_ID'::uuid)
 AND NOT EXISTS (SELECT 1 FROM entry_validations v WHERE v.wash_execution_id=e.wash_execution_id)
 AND NOT EXISTS (SELECT 1 FROM resource_assignments a WHERE a.wash_execution_id=e.wash_execution_id)
 AND EXISTS (SELECT 1 FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
  AND m.message_type='WashArrivalRegistered' AND m.aggregate_version=1 AND m.idempotency_key='$REGISTER_KEY' AND m.status='PUBLISHED');
SQL
        )
        [ "$proof" = 1 ] || fail "cleanup refused: pending execution is not this run's untouched arrival"
        expected_execution='PENDING_ENTRY|1'; expected_appointment='SCHEDULED|1'
        ;;
      *) fail "cleanup refused for execution outside the isolated authorized path" ;;
    esac
    attempts=0
    while :; do
      bff_state=$(sql "$bff_database" <<SQL
SELECT status || '|' || execution_version FROM wash_execution_read_model WHERE wash_execution_id='$execution_id'::uuid;
SQL
      )
      owner_state=$(sql "$appointments_database" <<SQL
SELECT status || '|' || version FROM appointments WHERE appointment_id='$APPOINTMENT_ID'::uuid;
SQL
      )
      bff_appointment=$(sql "$bff_database" <<SQL
SELECT status || '|' || source_version FROM appointment_read_model WHERE appointment_id='$APPOINTMENT_ID'::uuid;
SQL
      )
      [ "$bff_state" = "$expected_execution" ] && [ "$owner_state" = "$expected_appointment" ] \
        && [ "$bff_appointment" = "$expected_appointment" ] && break
      attempts=$((attempts+1)); [ "$attempts" -lt 30 ] || fail "cleanup refused before entry convergence"; sleep 1
    done
  fi

  resource_state=$(sql "$execution_database" <<SQL
SELECT (SELECT count(*) FROM cabin_projection WHERE cabin_id='$CABIN_ID'::uuid AND code='$CABIN_CODE'
 AND name='$CABIN_NAME' AND cabin_type='NORMAL' AND status='ACTIVE' AND source_version=1) || '|' ||
 (SELECT count(*) FROM tank_projection WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid
 AND code='$TANK_CODE' AND name='$TANK_NAME' AND configured_capacity=1 AND status='ACTIVE' AND source_version=1);
SQL
  )
  case "$resource_state" in '1|1'| '0|0') ;; *) fail "fixture resource state no longer matches manifest" ;; esac
  bff_resource_state=$(sql "$bff_database" <<SQL
SELECT (SELECT count(*) FROM wash_cabin_read_model WHERE cabin_id='$CABIN_ID'::uuid AND code='$CABIN_CODE'
 AND name='$CABIN_NAME' AND cabin_type='NORMAL' AND status='ACTIVE' AND source_version=1) || '|' ||
 (SELECT count(*) FROM wash_tank_read_model WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid
 AND code='$TANK_CODE' AND name='$TANK_NAME' AND configured_capacity=1 AND status='ACTIVE' AND source_version=1);
SQL
  )
  case "$bff_resource_state" in '1|1'| '0|0') ;; *) fail "BFF fixture resource state no longer matches manifest" ;; esac

  sql "$execution_database" >/dev/null <<SQL
BEGIN;
LOCK TABLE authorization_role_projection IN SHARE ROW EXCLUSIVE MODE;
DO \$guard\$ BEGIN
 IF NOT (( $ROLE_WAS_ABSENT=1 AND NOT EXISTS (SELECT 1 FROM authorization_role_projection WHERE account_id='$SUPERVISOR_ACCOUNT_ID'::uuid))
  OR EXISTS (SELECT 1 FROM authorization_role_projection WHERE account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND
   ((role_code='SUPERVISOR_LAVADO' AND role_source_version=CASE WHEN $ROLE_WAS_ABSENT=1 THEN 1 ELSE $ORIGINAL_ROLE_SOURCE_VERSION END
     AND updated_at=to_timestamp($FIXTURE_ROLE_UPDATED_EPOCH))
    OR ($ROLE_WAS_ABSENT=0 AND role_code='E2E_TESTER' AND role_source_version=$ORIGINAL_ROLE_SOURCE_VERSION
     AND updated_at=to_timestamp($ORIGINAL_ROLE_UPDATED_EPOCH))))) THEN
  RAISE EXCEPTION 'synthetic owner role changed before cleanup'; END IF;
END \$guard\$;
CREATE TEMP TABLE e2e_execution_ids ON COMMIT DROP AS
 SELECT wash_execution_id FROM wash_executions WHERE appointment_id='$APPOINTMENT_ID'::uuid;
DELETE FROM resource_unavailability_affected_executions WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM cleaning_periods WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM resource_assignments WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids)
 AND cabin_id='$CABIN_ID'::uuid AND tank_id='$TANK_ID'::uuid;
DELETE FROM entry_validations WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM outbox_messages WHERE idempotency_key IN ('$REGISTER_KEY','$DECIDE_KEY')
 OR aggregate_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM processed_messages WHERE idempotency_key IN ('$REGISTER_KEY','$DECIDE_KEY');
DELETE FROM processed_messages WHERE aggregate_id='$APPOINTMENT_ID'::uuid AND message_type='AppointmentScheduled';
DELETE FROM command_idempotency_records WHERE producer='bff' AND idempotency_key IN ('$REGISTER_KEY','$DECIDE_KEY');
DELETE FROM wash_executions WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM appointment_projection WHERE appointment_id='$APPOINTMENT_ID'::uuid
 AND student_account_id='$STUDENT_ACCOUNT_ID'::uuid AND appointment_time_slot_id='$SLOT_ID'::uuid;
DELETE FROM appointment_time_slot_projection WHERE appointment_time_slot_id='$SLOT_ID'::uuid AND calendar_id='$CALENDAR_ID'::uuid;
DELETE FROM tank_projection WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid AND code='$TANK_CODE';
DELETE FROM cabin_projection WHERE cabin_id='$CABIN_ID'::uuid AND code='$CABIN_CODE';
DELETE FROM authorization_access_projection WHERE account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
 AND system_id='$SYSTEM_ID'::uuid AND system_code='LAVADO_ULTRASONICO' AND access_status='ACTIVE' AND access_source_version=1;
$(if [ "$ROLE_WAS_ABSENT" = 1 ]; then
 printf "DELETE FROM authorization_role_projection WHERE account_id='%s'::uuid AND role_code='SUPERVISOR_LAVADO';" "$SUPERVISOR_ACCOUNT_ID"
 else
 printf "UPDATE authorization_role_projection SET role_code='E2E_TESTER',role_source_version=%s,updated_at=to_timestamp(%s) WHERE account_id='%s'::uuid AND role_code='SUPERVISOR_LAVADO';" "$ORIGINAL_ROLE_SOURCE_VERSION" "$ORIGINAL_ROLE_UPDATED_EPOCH" "$SUPERVISOR_ACCOUNT_ID"
 fi)
COMMIT;
SQL

  if [ "${E2E_TEST_FAIL_AFTER_OWNER_CLEANUP:-0}" = 1 ]; then fail "injected failure after Wash Execution cleanup"; fi

  sql "$bff_database" >/dev/null <<SQL
BEGIN;
CREATE TEMP TABLE e2e_operation_ids ON COMMIT DROP AS SELECT operation_id FROM client_operations
 WHERE actor_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND idempotency_key IN ('$REGISTER_KEY','$DECIDE_KEY');
CREATE TEMP TABLE e2e_execution_ids ON COMMIT DROP AS SELECT wash_execution_id FROM wash_execution_read_model
 WHERE appointment_id='$APPOINTMENT_ID'::uuid;
CREATE TEMP TABLE e2e_message_ids ON COMMIT DROP AS SELECT message_id FROM outbox_messages
 WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DELETE FROM inbox_messages WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids)
 OR aggregate_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM quarantined_messages WHERE message_id IN (SELECT message_id FROM e2e_message_ids);
DELETE FROM wash_resource_assignment_read_model WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids)
 AND cabin_id='$CABIN_ID'::uuid AND tank_id='$TANK_ID'::uuid;
DELETE FROM wash_execution_read_model WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM wash_tank_read_model WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid AND code='$TANK_CODE';
DELETE FROM wash_cabin_read_model WHERE cabin_id='$CABIN_ID'::uuid AND code='$CABIN_CODE';
DELETE FROM outbox_messages WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DELETE FROM idempotency_records WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DELETE FROM client_operations WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
COMMIT;
SQL
  rm -f -- "$manifest"
  printf 'Synthetic authorized-entry fixture cleaned.\n'
}

case "$mode" in
  bootstrap) bootstrap ;; audit) audit ;; cleanup) cleanup ;;
  *) fail "usage: $0 bootstrap|audit|cleanup" ;;
esac

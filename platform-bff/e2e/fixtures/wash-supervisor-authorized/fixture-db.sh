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
terminal_manifest=${E2E_AUTHORIZED_TERMINAL_MANIFEST:-}

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

manifest_value_from() {
  source_manifest=$1 key=$2
  value=$(sed -n "s/^${key}=//p" "$source_manifest")
  [ -n "$value" ] || fail "$source_manifest is missing $key"
  [ "$(printf '%s\n' "$value" | wc -l)" -eq 1 ] || fail "$source_manifest repeats $key"
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

load_terminal_manifest() {
  TERMINAL_MODE=0
  OPERATION_KEYS="'$REGISTER_KEY','$DECIDE_KEY'"
  WASH_EXECUTION_ID=00000000-0000-0000-0000-000000000000
  RESOURCE_ASSIGNMENT_ID=00000000-0000-0000-0000-000000000000
  INVALID_EXIT_KEY=unused-invalid-exit
  FOREIGN_EXIT_KEY=unused-foreign-exit
  EXIT_KEY=unused-exit
  COMPLETE_STALE_KEY=unused-complete-stale
  COMPLETE_KEY=unused-complete
  ORIGINAL_PACKAGE_COUNT=0
  ORIGINAL_CASSETTE8_COUNT=0
  ORIGINAL_CASSETTE10_COUNT=0
  ORIGINAL_WITNESS_COUNT=0
  FINAL_PACKAGE_COUNT=0
  FINAL_CASSETTE8_COUNT=0
  FINAL_CASSETTE10_COUNT=0
  FINAL_WITNESS_COUNT=0
  [ -n "$terminal_manifest" ] || return 0
  [ -f "$terminal_manifest" ] || fail "terminal fixture manifest does not exist"
  [ "$(stat -c %a "$terminal_manifest")" = 600 ] || fail "terminal fixture manifest must be mode 0600"
  TERMINAL_MODE=1
  WASH_EXECUTION_ID=$(manifest_value_from "$terminal_manifest" WASH_EXECUTION_ID)
  RESOURCE_ASSIGNMENT_ID=$(manifest_value_from "$terminal_manifest" RESOURCE_ASSIGNMENT_ID)
  terminal_appointment_id=$(manifest_value_from "$terminal_manifest" APPOINTMENT_ID)
  terminal_slot_id=$(manifest_value_from "$terminal_manifest" SLOT_ID)
  terminal_cabin_id=$(manifest_value_from "$terminal_manifest" CABIN_ID)
  terminal_tank_id=$(manifest_value_from "$terminal_manifest" TANK_ID)
  terminal_student_id=$(manifest_value_from "$terminal_manifest" STUDENT_ACCOUNT_ID)
  terminal_supervisor_id=$(manifest_value_from "$terminal_manifest" SUPERVISOR_ACCOUNT_ID)
  INVALID_EXIT_KEY=$(manifest_value_from "$terminal_manifest" INVALID_EXIT_KEY)
  FOREIGN_EXIT_KEY=$(manifest_value_from "$terminal_manifest" FOREIGN_EXIT_KEY)
  EXIT_KEY=$(manifest_value_from "$terminal_manifest" EXIT_KEY)
  COMPLETE_STALE_KEY=$(manifest_value_from "$terminal_manifest" COMPLETE_STALE_KEY)
  COMPLETE_KEY=$(manifest_value_from "$terminal_manifest" COMPLETE_KEY)
  ORIGINAL_PACKAGE_COUNT=$(manifest_value_from "$terminal_manifest" ORIGINAL_PACKAGE_COUNT)
  ORIGINAL_CASSETTE8_COUNT=$(manifest_value_from "$terminal_manifest" ORIGINAL_CASSETTE8_COUNT)
  ORIGINAL_CASSETTE10_COUNT=$(manifest_value_from "$terminal_manifest" ORIGINAL_CASSETTE10_COUNT)
  ORIGINAL_WITNESS_COUNT=$(manifest_value_from "$terminal_manifest" ORIGINAL_WITNESS_COUNT)
  FINAL_PACKAGE_COUNT=$(manifest_value_from "$terminal_manifest" FINAL_PACKAGE_COUNT)
  FINAL_CASSETTE8_COUNT=$(manifest_value_from "$terminal_manifest" FINAL_CASSETTE8_COUNT)
  FINAL_CASSETTE10_COUNT=$(manifest_value_from "$terminal_manifest" FINAL_CASSETTE10_COUNT)
  FINAL_WITNESS_COUNT=$(manifest_value_from "$terminal_manifest" FINAL_WITNESS_COUNT)
  for pair in "$WASH_EXECUTION_ID:WASH_EXECUTION_ID" "$RESOURCE_ASSIGNMENT_ID:RESOURCE_ASSIGNMENT_ID" \
      "$terminal_appointment_id:APPOINTMENT_ID" "$terminal_slot_id:SLOT_ID" \
      "$terminal_cabin_id:CABIN_ID" "$terminal_tank_id:TANK_ID" \
      "$terminal_student_id:STUDENT_ACCOUNT_ID" "$terminal_supervisor_id:SUPERVISOR_ACCOUNT_ID"; do
    require_uuid "${pair%%:*}" "${pair#*:}"
  done
  [ "$APPOINTMENT_ID" = "$terminal_appointment_id" ] || fail "terminal appointment does not match entry fixture"
  [ "$SLOT_ID" = "$terminal_slot_id" ] || fail "terminal slot does not match entry fixture"
  [ "$CABIN_ID" = "$terminal_cabin_id" ] || fail "terminal cabin does not match entry fixture"
  [ "$TANK_ID" = "$terminal_tank_id" ] || fail "terminal tank does not match entry fixture"
  [ "$STUDENT_ACCOUNT_ID" = "$terminal_student_id" ] || fail "terminal student does not match entry fixture"
  [ "$SUPERVISOR_ACCOUNT_ID" = "$terminal_supervisor_id" ] || fail "terminal supervisor does not match entry fixture"
  [ "$INVALID_EXIT_KEY" = "$RUN_KEY-invalid-exit" ] || fail "unexpected invalid-exit key"
  [ "$FOREIGN_EXIT_KEY" = "$RUN_KEY-foreign-exit" ] || fail "unexpected foreign-exit key"
  [ "$EXIT_KEY" = "$RUN_KEY-exit" ] || fail "unexpected exit key"
  [ "$COMPLETE_STALE_KEY" = "$RUN_KEY-complete-stale" ] || fail "unexpected stale-complete key"
  [ "$COMPLETE_KEY" = "$RUN_KEY-complete" ] || fail "unexpected complete key"
  for value in "$ORIGINAL_PACKAGE_COUNT" "$ORIGINAL_CASSETTE8_COUNT" "$ORIGINAL_CASSETTE10_COUNT" \
      "$ORIGINAL_WITNESS_COUNT" "$FINAL_PACKAGE_COUNT" "$FINAL_CASSETTE8_COUNT" \
      "$FINAL_CASSETTE10_COUNT" "$FINAL_WITNESS_COUNT"; do
    case "$value" in ''|*[!0-9]*) fail "terminal materials must be nonnegative integers" ;; esac
  done
  [ "$ORIGINAL_PACKAGE_COUNT|$ORIGINAL_CASSETTE8_COUNT|$ORIGINAL_CASSETTE10_COUNT|$ORIGINAL_WITNESS_COUNT" = '2|1|0|2' ] \
    || fail "unexpected original materials"
  [ "$FINAL_PACKAGE_COUNT|$FINAL_CASSETTE8_COUNT|$FINAL_CASSETTE10_COUNT|$FINAL_WITNESS_COUNT" = '2|1|1|2' ] \
    || fail "unexpected final materials"
  OPERATION_KEYS="'$REGISTER_KEY','$DECIDE_KEY','$INVALID_EXIT_KEY','$FOREIGN_EXIT_KEY','$EXIT_KEY','$COMPLETE_STALE_KEY','$COMPLETE_KEY'"
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
  arrival_message_id=$(sql "$execution_database" <<SQL
SELECT message_id FROM outbox_messages WHERE aggregate_id='$execution_id'::uuid
 AND message_type='WashArrivalRegistered' AND message_kind='EVENT' AND aggregate_version=1;
SQL
  )
  authorized_message_id=$(sql "$execution_database" <<SQL
SELECT message_id FROM outbox_messages WHERE aggregate_id='$execution_id'::uuid
 AND message_type='WashEntryAuthorized' AND message_kind='EVENT' AND aggregate_version=2;
SQL
  )
  assignment_message_id=$(sql "$execution_database" <<SQL
SELECT message_id FROM outbox_messages WHERE aggregate_id='$execution_id'::uuid
 AND message_type='WashResourceAssigned' AND message_kind='EVENT' AND aggregate_version=2;
SQL
  )
  started_message_id=$(sql "$execution_database" <<SQL
SELECT message_id FROM outbox_messages WHERE aggregate_id='$execution_id'::uuid
 AND message_type='WashExecutionStarted' AND message_kind='EVENT' AND aggregate_version=2;
SQL
  )
  require_uuid "$arrival_message_id" ARRIVAL_MESSAGE_ID
  require_uuid "$authorized_message_id" AUTHORIZED_MESSAGE_ID
  require_uuid "$assignment_message_id" ASSIGNMENT_MESSAGE_ID
  require_uuid "$started_message_id" STARTED_MESSAGE_ID
  umask 077
  cp "$manifest" "$report_dir/entry-audit.env"
  {
    printf 'WASH_EXECUTION_ID=%s\n' "$execution_id"
    printf 'RESOURCE_ASSIGNMENT_ID=%s\n' "$resource_assignment_id"
    printf 'ARRIVAL_MESSAGE_ID=%s\n' "$arrival_message_id"
    printf 'AUTHORIZED_MESSAGE_ID=%s\n' "$authorized_message_id"
    printf 'ASSIGNMENT_MESSAGE_ID=%s\n' "$assignment_message_id"
    printf 'STARTED_MESSAGE_ID=%s\n' "$started_message_id"
  } >> "$report_dir/entry-audit.env"
  printf 'Authorized supervisor entry audit passed.\n'
}

cleanup() {
  require_runtime
  [ -f "$student_manifest" ] || fail "entry cleanup must run before student cleanup"
  [ -f "$supervisor_manifest" ] || fail "entry cleanup must run before supervisor cleanup"
  load_manifest
  load_terminal_manifest

  attempts=0
  while :; do
    pending_bff=$(sql "$bff_database" <<SQL
SELECT count(*) FROM client_operations WHERE idempotency_key IN ($OPERATION_KEYS) AND status='PENDING';
SQL
    )
    pending_execution=$(sql "$execution_database" <<SQL
SELECT count(*) FROM outbox_messages WHERE (idempotency_key IN ($OPERATION_KEYS)
 OR aggregate_id IN (SELECT wash_execution_id FROM wash_executions WHERE appointment_id='$APPOINTMENT_ID'::uuid))
 AND status <> 'PUBLISHED'
UNION ALL SELECT count(*) FROM processed_messages
 WHERE idempotency_key IN ($OPERATION_KEYS) AND status='DEFERRED';
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
        expected_bff_consumed='1|1|1|1'
        if [ "$TERMINAL_MODE" = 1 ]; then
          expected_bff_consumed='1|1|1|1|0|0'
          # A rejected exit is safe to abandon only after the owner proves no transition.
          rejected_exit=$(sql "$execution_database" <<SQL
SELECT count(*) FROM command_idempotency_records
WHERE producer='bff' AND operation_type='SubmitWashExit' AND idempotency_key='$EXIT_KEY'
 AND status='REJECTED' AND error_code='ACTOR_NOT_AUTHORIZED' AND completed_at IS NOT NULL;
SQL
          )
          [ "$rejected_exit" = 1 ] || fail "cleanup refused: initial exit rejection is not proven by owner"
        fi
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
        expected_bff_consumed='1|0|0|0'
        ;;
      'EXIT_SUBMITTED|3')
        [ "$TERMINAL_MODE" = 1 ] || fail "terminal cleanup manifest is required for EXIT_SUBMITTED"
        [ "$execution_id" = "$WASH_EXECUTION_ID" ] || fail "terminal execution does not match manifest"
        proof=$(sql "$execution_database" <<SQL
SELECT count(*) FROM wash_executions e
JOIN entry_validations v ON v.wash_execution_id=e.wash_execution_id
JOIN resource_assignments a ON a.resource_assignment_id='$RESOURCE_ASSIGNMENT_ID'::uuid
WHERE e.wash_execution_id='$WASH_EXECUTION_ID'::uuid AND e.appointment_id='$APPOINTMENT_ID'::uuid
 AND e.exit_submitted_by_account_id='$STUDENT_ACCOUNT_ID'::uuid AND e.exit_submitted_at IS NOT NULL
 AND e.package_count=$ORIGINAL_PACKAGE_COUNT AND e.green_paper_cassette_8_count=$ORIGINAL_CASSETTE8_COUNT
 AND e.green_paper_cassette_10_count=$ORIGINAL_CASSETTE10_COUNT AND e.witness_tape_portion_count=$ORIGINAL_WITNESS_COUNT
 AND e.completed_by_account_id IS NULL AND e.completed_at IS NULL
 AND v.decision='AUTHORIZED' AND v.decided_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
 AND a.wash_execution_id=e.wash_execution_id AND a.appointment_time_slot_id='$SLOT_ID'::uuid
 AND a.cabin_id='$CABIN_ID'::uuid AND a.tank_id='$TANK_ID'::uuid AND a.assignment_type='INITIAL'
 AND a.released_at IS NULL AND a.released_by_account_id IS NULL AND a.release_reason IS NULL
 AND (SELECT count(*) FROM resource_assignments x WHERE x.wash_execution_id=e.wash_execution_id)=1
 AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id AND m.message_kind='EVENT')=5
 AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
   AND m.message_type='WashExitSubmitted' AND m.aggregate_version=3 AND m.idempotency_key='$EXIT_KEY'
   AND m.status='PUBLISHED' AND (m.payload_json->>'packageCount')::int=$ORIGINAL_PACKAGE_COUNT
   AND (m.payload_json->>'greenPaperCassette8Count')::int=$ORIGINAL_CASSETTE8_COUNT
   AND (m.payload_json->>'greenPaperCassette10Count')::int=$ORIGINAL_CASSETTE10_COUNT
   AND (m.payload_json->>'witnessTapePortionCount')::int=$ORIGINAL_WITNESS_COUNT)=1
 AND (SELECT count(*) FROM command_idempotency_records WHERE producer='bff'
   AND idempotency_key='$EXIT_KEY' AND status='SUCCEEDED' AND aggregate_id=e.wash_execution_id)=1
 AND NOT EXISTS (SELECT 1 FROM command_idempotency_records WHERE producer='bff'
   AND idempotency_key IN ('$COMPLETE_STALE_KEY','$COMPLETE_KEY') AND status='IN_PROGRESS');
SQL
        )
        [ "$proof" = 1 ] || fail "cleanup refused: EXIT_SUBMITTED history is not the exact terminal fixture"
        expected_execution='EXIT_SUBMITTED|3'; expected_appointment='IN_PROGRESS|3'
        expected_bff_consumed='1|1|1|1|1|0'
        ;;
      'COMPLETED|4')
        [ "$TERMINAL_MODE" = 1 ] || fail "terminal cleanup manifest is required for COMPLETED"
        [ "$execution_id" = "$WASH_EXECUTION_ID" ] || fail "terminal execution does not match manifest"
        proof=$(sql "$execution_database" <<SQL
SELECT count(*) FROM wash_executions e
JOIN entry_validations v ON v.wash_execution_id=e.wash_execution_id
JOIN resource_assignments a ON a.resource_assignment_id='$RESOURCE_ASSIGNMENT_ID'::uuid
WHERE e.wash_execution_id='$WASH_EXECUTION_ID'::uuid AND e.appointment_id='$APPOINTMENT_ID'::uuid
 AND e.exit_submitted_by_account_id='$STUDENT_ACCOUNT_ID'::uuid AND e.exit_submitted_at IS NOT NULL
 AND e.completed_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND e.completed_at IS NOT NULL
 AND e.package_count=$FINAL_PACKAGE_COUNT AND e.green_paper_cassette_8_count=$FINAL_CASSETTE8_COUNT
 AND e.green_paper_cassette_10_count=$FINAL_CASSETTE10_COUNT AND e.witness_tape_portion_count=$FINAL_WITNESS_COUNT
 AND v.decision='AUTHORIZED' AND v.decided_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
 AND a.wash_execution_id=e.wash_execution_id AND a.appointment_time_slot_id='$SLOT_ID'::uuid
 AND a.cabin_id='$CABIN_ID'::uuid AND a.tank_id='$TANK_ID'::uuid AND a.assignment_type='INITIAL'
 AND a.released_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND a.released_at=e.completed_at
 AND a.release_reason='NORMAL_COMPLETION'
 AND (SELECT count(*) FROM resource_assignments x WHERE x.wash_execution_id=e.wash_execution_id)=1
 AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id AND m.message_kind='EVENT')=6
 AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
   AND m.message_type='WashExitSubmitted' AND m.aggregate_version=3 AND m.idempotency_key='$EXIT_KEY'
   AND m.status='PUBLISHED' AND (m.payload_json->>'packageCount')::int=$ORIGINAL_PACKAGE_COUNT
   AND (m.payload_json->>'greenPaperCassette8Count')::int=$ORIGINAL_CASSETTE8_COUNT
   AND (m.payload_json->>'greenPaperCassette10Count')::int=$ORIGINAL_CASSETTE10_COUNT
   AND (m.payload_json->>'witnessTapePortionCount')::int=$ORIGINAL_WITNESS_COUNT)=1
 AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
   AND m.message_type='WashExecutionCompleted' AND m.aggregate_version=4 AND m.idempotency_key='$COMPLETE_KEY'
   AND m.status='PUBLISHED' AND (m.payload_json->>'finalPackageCount')::int=$FINAL_PACKAGE_COUNT
   AND (m.payload_json->>'finalGreenPaperCassette8Count')::int=$FINAL_CASSETTE8_COUNT
   AND (m.payload_json->>'finalGreenPaperCassette10Count')::int=$FINAL_CASSETTE10_COUNT
   AND (m.payload_json->>'finalWitnessTapePortionCount')::int=$FINAL_WITNESS_COUNT)=1
 AND (SELECT count(*) FROM command_idempotency_records WHERE producer='bff'
   AND idempotency_key='$EXIT_KEY' AND status='SUCCEEDED' AND aggregate_id=e.wash_execution_id)=1
 AND (SELECT count(*) FROM command_idempotency_records WHERE producer='bff'
   AND idempotency_key='$COMPLETE_STALE_KEY' AND status='REJECTED' AND error_code='VERSION_CONFLICT')=1
 AND (SELECT count(*) FROM command_idempotency_records WHERE producer='bff'
   AND idempotency_key='$COMPLETE_KEY' AND status='SUCCEEDED' AND aggregate_id=e.wash_execution_id)=1;
SQL
        )
        [ "$proof" = 1 ] || fail "cleanup refused: COMPLETED history is not the exact terminal fixture"
        expected_execution='COMPLETED|4'; expected_appointment='COMPLETED|4'
        expected_bff_consumed='1|1|1|1|1|1'
        ;;
      *) fail "cleanup refused for execution outside the isolated authorized path" ;;
    esac
    if [ "$TERMINAL_MODE" = 1 ]; then
      operation_proof=$(sql "$bff_database" <<SQL
SELECT
 (SELECT count(*) FROM client_operations WHERE idempotency_key IN ('$INVALID_EXIT_KEY','$FOREIGN_EXIT_KEY')) || '|' ||
 (SELECT count(*) FROM client_operations WHERE actor_account_id='$STUDENT_ACCOUNT_ID'::uuid
   AND idempotency_key='$EXIT_KEY' AND public_operation='wash-submit-exit' AND status='SUCCEEDED') || '|' ||
 (SELECT count(*) FROM client_operations WHERE actor_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
   AND idempotency_key='$COMPLETE_STALE_KEY' AND public_operation='wash-complete-execution'
   AND status='REJECTED' AND error_code='VERSION_CONFLICT') || '|' ||
 (SELECT count(*) FROM client_operations WHERE actor_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
   AND idempotency_key='$COMPLETE_KEY' AND public_operation='wash-complete-execution' AND status='SUCCEEDED') || '|' ||
 (SELECT count(*) FROM client_operations WHERE idempotency_key IN ('$EXIT_KEY','$COMPLETE_STALE_KEY','$COMPLETE_KEY'));
SQL
      )
      case "$expected_execution:$operation_proof" in
        'IN_PROGRESS|2:0|0|0|0|1')
          rejected_exit=$(sql "$bff_database" <<SQL
SELECT count(*) FROM client_operations WHERE actor_account_id='$STUDENT_ACCOUNT_ID'::uuid
 AND idempotency_key='$EXIT_KEY' AND public_operation='wash-submit-exit'
 AND status='REJECTED' AND error_code='ACTOR_NOT_AUTHORIZED' AND completed_at IS NOT NULL;
SQL
          )
          [ "$rejected_exit" = 1 ] || fail "cleanup refused: initial exit rejection is not proven by BFF"
          ;;
        'EXIT_SUBMITTED|3:0|1|0|0|1'|'EXIT_SUBMITTED|3:0|1|1|0|2') ;;
        'COMPLETED|4:0|1|1|1|3') ;;
        *) fail "cleanup refused: terminal BFF operations are missing or ambiguous" ;;
      esac
    fi
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
      if [ "$TERMINAL_MODE" = 1 ]; then
        appointments_consumed=$(sql "$appointments_database" <<SQL
SELECT count(*) FROM wash_execution_evidence_projection e
JOIN wash_execution_transition_projection started ON started.wash_execution_id=e.wash_execution_id
JOIN processed_messages started_message ON started_message.message_id=started.source_event_id
WHERE e.appointment_id='$APPOINTMENT_ID'::uuid AND e.wash_execution_id='$execution_id'::uuid
 AND e.arrival_execution_version=1 AND e.entry_authorized_execution_version=2
 AND started.event_type='WashExecutionStarted' AND started.execution_version=2
 AND started_message.status='APPLIED'
 AND ($([ "$expected_execution" = 'COMPLETED|4' ] && printf "EXISTS (SELECT 1 FROM wash_execution_transition_projection completed JOIN processed_messages pm ON pm.message_id=completed.source_event_id WHERE completed.wash_execution_id=e.wash_execution_id AND completed.event_type='WashExecutionCompleted' AND completed.execution_version=4 AND completed.state='COMPLETED' AND pm.status='APPLIED')" || printf 'TRUE'));
SQL
        )
      elif [ "$expected_execution" = 'IN_PROGRESS|2' ]; then
        appointments_consumed=$(sql "$appointments_database" <<SQL
SELECT count(*) FROM wash_execution_evidence_projection e
JOIN processed_messages arrival ON arrival.message_id=e.arrival_source_event_id
JOIN processed_messages authorized ON authorized.message_id=e.entry_authorized_source_event_id
JOIN wash_execution_transition_projection started
  ON started.wash_execution_id=e.wash_execution_id AND started.appointment_id=e.appointment_id
JOIN processed_messages started_message ON started_message.message_id=started.source_event_id
WHERE e.appointment_id='$APPOINTMENT_ID'::uuid AND e.wash_execution_id='$execution_id'::uuid
 AND e.arrival_execution_version=1 AND e.entry_authorized_execution_version=2
 AND arrival.message_type='WashArrivalRegistered' AND arrival.status='APPLIED'
 AND authorized.message_type='WashEntryAuthorized' AND authorized.status='APPLIED'
 AND started.event_type='WashExecutionStarted' AND started.execution_version=2 AND started.state='IN_PROGRESS'
 AND started_message.message_type='WashExecutionStarted' AND started_message.status='APPLIED';
SQL
        )
      else
        appointments_consumed=$(sql "$appointments_database" <<SQL
SELECT count(*) FROM wash_execution_evidence_projection e
JOIN processed_messages arrival ON arrival.message_id=e.arrival_source_event_id
WHERE e.appointment_id='$APPOINTMENT_ID'::uuid AND e.wash_execution_id='$execution_id'::uuid
 AND e.arrival_execution_version=1
 AND arrival.message_type='WashArrivalRegistered' AND arrival.status='APPLIED';
SQL
        )
      fi
      bff_consumed=$(sql "$bff_database" <<SQL
SELECT
 (SELECT count(*) FROM inbox_messages WHERE consumer_name='bff-projection-runtime'
   AND aggregate_id='$execution_id'::uuid AND message_kind='EVENT'
   AND message_type='WashArrivalRegistered' AND aggregate_version=1) || '|' ||
 (SELECT count(*) FROM inbox_messages WHERE consumer_name='bff-projection-runtime'
   AND aggregate_id='$execution_id'::uuid AND message_kind='EVENT'
   AND message_type='WashEntryAuthorized' AND aggregate_version=2) || '|' ||
 (SELECT count(*) FROM inbox_messages WHERE consumer_name='bff-projection-runtime'
   AND aggregate_id='$execution_id'::uuid AND message_kind='EVENT'
   AND message_type='WashResourceAssigned' AND aggregate_version=2) || '|' ||
 (SELECT count(*) FROM inbox_messages WHERE consumer_name='bff-projection-runtime'
   AND aggregate_id='$execution_id'::uuid AND message_kind='EVENT'
   AND message_type='WashExecutionStarted' AND aggregate_version=2) || '|' ||
 (SELECT count(*) FROM inbox_messages WHERE consumer_name='bff-projection-runtime'
   AND aggregate_id='$execution_id'::uuid AND message_kind='EVENT'
   AND message_type='WashExitSubmitted' AND aggregate_version=3) || '|' ||
 (SELECT count(*) FROM inbox_messages WHERE consumer_name='bff-projection-runtime'
   AND aggregate_id='$execution_id'::uuid AND message_kind='EVENT'
   AND message_type='WashExecutionCompleted' AND aggregate_version=4);
SQL
      )
      if [ "$TERMINAL_MODE" = 0 ]; then
        [ "$(printf '%s\n' "$bff_consumed" | awk -F'|' '{print NF}')" -ne 6 ] \
          || bff_consumed=${bff_consumed%|0|0}
      fi
      [ "$bff_state" = "$expected_execution" ] && [ "$owner_state" = "$expected_appointment" ] \
        && [ "$bff_appointment" = "$expected_appointment" ] && [ "$appointments_consumed" = 1 ] \
        && [ "$bff_consumed" = "$expected_bff_consumed" ] && break
      attempts=$((attempts+1)); [ "$attempts" -lt 30 ] || fail "cleanup refused before entry convergence"; sleep 1
    done
  fi

  sql "$execution_database" >/dev/null <<SQL
BEGIN;
LOCK TABLE wash_executions, entry_validations, resource_assignments,
  resource_unavailability_affected_executions, cleaning_periods, outbox_messages,
  cabin_projection, tank_projection IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE e2e_execution_ids ON COMMIT DROP AS
 SELECT wash_execution_id FROM wash_executions WHERE appointment_id='$APPOINTMENT_ID'::uuid;
DO \$guard\$ BEGIN
 IF NOT (
   ((SELECT count(*) FROM cabin_projection WHERE cabin_id='$CABIN_ID'::uuid OR code='$CABIN_CODE')=0
    AND (SELECT count(*) FROM tank_projection WHERE tank_id='$TANK_ID'::uuid OR code='$TANK_CODE')=0)
   OR
   ((SELECT count(*) FROM cabin_projection WHERE cabin_id='$CABIN_ID'::uuid OR code='$CABIN_CODE')=1
    AND (SELECT count(*) FROM cabin_projection WHERE cabin_id='$CABIN_ID'::uuid AND code='$CABIN_CODE'
      AND name='$CABIN_NAME' AND cabin_type='NORMAL' AND status='ACTIVE' AND source_version=1)=1
    AND (SELECT count(*) FROM tank_projection WHERE tank_id='$TANK_ID'::uuid OR code='$TANK_CODE')=1
    AND (SELECT count(*) FROM tank_projection WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid
      AND code='$TANK_CODE' AND name='$TANK_NAME' AND configured_capacity=1
      AND status='ACTIVE' AND source_version=1)=1)
 ) THEN RAISE EXCEPTION 'fixture resource state no longer matches manifest'; END IF;

 IF EXISTS (SELECT 1 FROM e2e_execution_ids) AND NOT (
   EXISTS (
    SELECT 1 FROM wash_executions e
    JOIN entry_validations v ON v.wash_execution_id=e.wash_execution_id
    JOIN resource_assignments a ON a.wash_execution_id=e.wash_execution_id
    WHERE e.wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids)
      AND e.status='IN_PROGRESS' AND e.version=2 AND e.started_at IS NOT NULL
      AND v.decision='AUTHORIZED' AND v.identity_confirmed AND v.requirements_satisfied
      AND v.rejection_reason IS NULL AND v.decided_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
      AND a.appointment_time_slot_id='$SLOT_ID'::uuid AND a.cabin_id='$CABIN_ID'::uuid
      AND a.tank_id='$TANK_ID'::uuid AND a.assignment_type='INITIAL'
      AND a.assigned_by_account_id IS NULL AND a.released_at IS NULL
      AND (SELECT count(*) FROM resource_assignments x WHERE x.wash_execution_id=e.wash_execution_id)=1
      AND (SELECT count(*) FROM outbox_messages m
        WHERE m.aggregate_id=e.wash_execution_id AND m.message_kind='EVENT')=4
      AND EXISTS (SELECT 1 FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
        AND m.message_type='WashArrivalRegistered' AND m.message_kind='EVENT' AND m.aggregate_version=1
        AND m.idempotency_key='$REGISTER_KEY' AND m.status='PUBLISHED')
      AND EXISTS (SELECT 1 FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
        AND m.message_type='WashEntryAuthorized' AND m.message_kind='EVENT' AND m.aggregate_version=2
        AND m.idempotency_key='$DECIDE_KEY' AND m.status='PUBLISHED')
      AND EXISTS (SELECT 1 FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
        AND m.message_type='WashResourceAssigned' AND m.message_kind='EVENT' AND m.aggregate_version=2
        AND m.idempotency_key='$DECIDE_KEY' AND m.status='PUBLISHED')
      AND EXISTS (SELECT 1 FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
        AND m.message_type='WashExecutionStarted' AND m.message_kind='EVENT' AND m.aggregate_version=2
        AND m.idempotency_key='$DECIDE_KEY' AND m.status='PUBLISHED')
   )
   OR EXISTS (
    SELECT 1 FROM wash_executions e
    WHERE e.wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids)
      AND e.status='PENDING_ENTRY' AND e.version=1 AND e.started_at IS NULL
      AND EXISTS (SELECT 1 FROM appointment_projection p WHERE p.appointment_id=e.appointment_id
        AND p.student_account_id='$STUDENT_ACCOUNT_ID'::uuid
        AND p.appointment_time_slot_id='$SLOT_ID'::uuid)
      AND NOT EXISTS (SELECT 1 FROM entry_validations v WHERE v.wash_execution_id=e.wash_execution_id)
      AND NOT EXISTS (SELECT 1 FROM resource_assignments a WHERE a.wash_execution_id=e.wash_execution_id)
      AND (SELECT count(*) FROM outbox_messages m
        WHERE m.aggregate_id=e.wash_execution_id AND m.message_kind='EVENT')=1
      AND EXISTS (SELECT 1 FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
        AND m.message_type='WashArrivalRegistered' AND m.message_kind='EVENT' AND m.aggregate_version=1
        AND m.idempotency_key='$REGISTER_KEY' AND m.status='PUBLISHED')
   )
   OR ($TERMINAL_MODE=1 AND EXISTS (
    SELECT 1 FROM wash_executions e
    JOIN entry_validations v ON v.wash_execution_id=e.wash_execution_id
    JOIN resource_assignments a ON a.resource_assignment_id='$RESOURCE_ASSIGNMENT_ID'::uuid
    WHERE e.wash_execution_id='$WASH_EXECUTION_ID'::uuid AND e.appointment_id='$APPOINTMENT_ID'::uuid
      AND v.decision='AUTHORIZED' AND v.decided_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
      AND a.wash_execution_id=e.wash_execution_id AND a.appointment_time_slot_id='$SLOT_ID'::uuid
      AND a.cabin_id='$CABIN_ID'::uuid AND a.tank_id='$TANK_ID'::uuid AND a.assignment_type='INITIAL'
      AND (SELECT count(*) FROM resource_assignments x WHERE x.wash_execution_id=e.wash_execution_id)=1
      AND (
       (e.status='EXIT_SUBMITTED' AND e.version=3
        AND e.exit_submitted_by_account_id='$STUDENT_ACCOUNT_ID'::uuid AND e.exit_submitted_at IS NOT NULL
        AND e.package_count=$ORIGINAL_PACKAGE_COUNT AND e.green_paper_cassette_8_count=$ORIGINAL_CASSETTE8_COUNT
        AND e.green_paper_cassette_10_count=$ORIGINAL_CASSETTE10_COUNT AND e.witness_tape_portion_count=$ORIGINAL_WITNESS_COUNT
        AND e.completed_at IS NULL AND a.released_at IS NULL
        AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id AND m.message_kind='EVENT')=5)
       OR
       (e.status='COMPLETED' AND e.version=4
        AND e.exit_submitted_by_account_id='$STUDENT_ACCOUNT_ID'::uuid AND e.exit_submitted_at IS NOT NULL
        AND e.completed_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND e.completed_at IS NOT NULL
        AND e.package_count=$FINAL_PACKAGE_COUNT AND e.green_paper_cassette_8_count=$FINAL_CASSETTE8_COUNT
        AND e.green_paper_cassette_10_count=$FINAL_CASSETTE10_COUNT AND e.witness_tape_portion_count=$FINAL_WITNESS_COUNT
        AND a.released_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND a.released_at=e.completed_at
        AND a.release_reason='NORMAL_COMPLETION'
        AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id AND m.message_kind='EVENT')=6)
      )
      AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
        AND m.message_type='WashExitSubmitted' AND m.aggregate_version=3
        AND m.idempotency_key='$EXIT_KEY' AND m.status='PUBLISHED')=1
      AND (e.status<>'COMPLETED' OR (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id
        AND m.message_type='WashExecutionCompleted' AND m.aggregate_version=4
        AND m.idempotency_key='$COMPLETE_KEY' AND m.status='PUBLISHED')=1)
   ))
 ) THEN RAISE EXCEPTION 'execution changed after cleanup proof'; END IF;
END \$guard\$;
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
DELETE FROM resource_unavailability_affected_executions WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM cleaning_periods WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM resource_assignments WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids)
 AND cabin_id='$CABIN_ID'::uuid AND tank_id='$TANK_ID'::uuid;
DELETE FROM entry_validations WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM outbox_messages WHERE idempotency_key IN ($OPERATION_KEYS)
 OR aggregate_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM processed_messages WHERE idempotency_key IN ($OPERATION_KEYS);
DELETE FROM processed_messages WHERE aggregate_id='$APPOINTMENT_ID'::uuid AND message_type='AppointmentScheduled';
DELETE FROM command_idempotency_records WHERE producer='bff' AND idempotency_key IN ($OPERATION_KEYS);
DELETE FROM wash_executions WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM appointment_projection WHERE appointment_id='$APPOINTMENT_ID'::uuid
 AND student_account_id='$STUDENT_ACCOUNT_ID'::uuid AND appointment_time_slot_id='$SLOT_ID'::uuid;
DELETE FROM appointment_time_slot_projection WHERE appointment_time_slot_id='$SLOT_ID'::uuid AND calendar_id='$CALENDAR_ID'::uuid;
DELETE FROM tank_projection WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid
 AND code='$TANK_CODE' AND name='$TANK_NAME' AND configured_capacity=1 AND status='ACTIVE' AND source_version=1;
DELETE FROM cabin_projection WHERE cabin_id='$CABIN_ID'::uuid AND code='$CABIN_CODE'
 AND name='$CABIN_NAME' AND cabin_type='NORMAL' AND status='ACTIVE' AND source_version=1;
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
LOCK TABLE client_operations, inbox_messages, quarantined_messages, outbox_messages,
  idempotency_records, wash_execution_read_model, wash_resource_assignment_read_model,
  wash_cabin_read_model, wash_tank_read_model IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE e2e_operation_ids ON COMMIT DROP AS SELECT operation_id FROM client_operations
 WHERE idempotency_key IN ($OPERATION_KEYS)
   AND actor_account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid);
CREATE TEMP TABLE e2e_execution_ids ON COMMIT DROP AS SELECT wash_execution_id FROM wash_execution_read_model
 WHERE appointment_id='$APPOINTMENT_ID'::uuid;
CREATE TEMP TABLE e2e_message_ids ON COMMIT DROP AS SELECT message_id FROM outbox_messages
 WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DO \$guard\$ BEGIN
 IF NOT (
   ((SELECT count(*) FROM wash_cabin_read_model WHERE cabin_id='$CABIN_ID'::uuid OR code='$CABIN_CODE')=0
    AND (SELECT count(*) FROM wash_tank_read_model WHERE tank_id='$TANK_ID'::uuid OR code='$TANK_CODE')=0)
   OR
   ((SELECT count(*) FROM wash_cabin_read_model WHERE cabin_id='$CABIN_ID'::uuid OR code='$CABIN_CODE')=1
    AND (SELECT count(*) FROM wash_cabin_read_model WHERE cabin_id='$CABIN_ID'::uuid AND code='$CABIN_CODE'
      AND name='$CABIN_NAME' AND cabin_type='NORMAL' AND status='ACTIVE' AND source_version=1)=1
    AND (SELECT count(*) FROM wash_tank_read_model WHERE tank_id='$TANK_ID'::uuid OR code='$TANK_CODE')=1
    AND (SELECT count(*) FROM wash_tank_read_model WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid
      AND code='$TANK_CODE' AND name='$TANK_NAME' AND configured_capacity=1
      AND status='ACTIVE' AND source_version=1)=1)
 ) THEN RAISE EXCEPTION 'BFF fixture resource state no longer matches manifest'; END IF;

 IF EXISTS (SELECT 1 FROM e2e_execution_ids) AND NOT (
   EXISTS (SELECT 1 FROM wash_execution_read_model e
    JOIN wash_resource_assignment_read_model a ON a.wash_execution_id=e.wash_execution_id
    WHERE e.wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids)
      AND e.status='IN_PROGRESS' AND e.execution_version=2
      AND a.appointment_time_slot_id='$SLOT_ID'::uuid AND a.cabin_id='$CABIN_ID'::uuid
      AND a.tank_id='$TANK_ID'::uuid AND a.assignment_type='INITIAL'
      AND a.released_at IS NULL AND a.source_version=2
      AND (SELECT count(*) FROM wash_resource_assignment_read_model x
        WHERE x.wash_execution_id=e.wash_execution_id)=1)
   OR EXISTS (SELECT 1 FROM wash_execution_read_model e
    WHERE e.wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids)
      AND e.status='PENDING_ENTRY' AND e.execution_version=1
      AND NOT EXISTS (SELECT 1 FROM wash_resource_assignment_read_model a
        WHERE a.wash_execution_id=e.wash_execution_id))
   OR ($TERMINAL_MODE=1 AND EXISTS (SELECT 1 FROM wash_execution_read_model e
    JOIN wash_resource_assignment_read_model a ON a.resource_assignment_id='$RESOURCE_ASSIGNMENT_ID'::uuid
    WHERE e.wash_execution_id='$WASH_EXECUTION_ID'::uuid AND e.appointment_id='$APPOINTMENT_ID'::uuid
      AND a.wash_execution_id=e.wash_execution_id AND a.appointment_time_slot_id='$SLOT_ID'::uuid
      AND a.cabin_id='$CABIN_ID'::uuid AND a.tank_id='$TANK_ID'::uuid AND a.assignment_type='INITIAL'
      AND (SELECT count(*) FROM wash_resource_assignment_read_model x
        WHERE x.wash_execution_id=e.wash_execution_id)=1
      AND e.submitted_exit_version=3
      AND e.submitted_package_count=$ORIGINAL_PACKAGE_COUNT
      AND e.submitted_green_paper_cassette_8_count=$ORIGINAL_CASSETTE8_COUNT
      AND e.submitted_green_paper_cassette_10_count=$ORIGINAL_CASSETTE10_COUNT
      AND e.submitted_witness_tape_portion_count=$ORIGINAL_WITNESS_COUNT
      AND ((e.status='EXIT_SUBMITTED' AND e.execution_version=3 AND e.final_exit_version=0
            AND a.released_at IS NULL)
        OR (e.status='COMPLETED' AND e.execution_version=4 AND e.final_exit_version=4
            AND e.final_package_count=$FINAL_PACKAGE_COUNT
            AND e.final_green_paper_cassette_8_count=$FINAL_CASSETTE8_COUNT
            AND e.final_green_paper_cassette_10_count=$FINAL_CASSETTE10_COUNT
            AND e.final_witness_tape_portion_count=$FINAL_WITNESS_COUNT
            AND a.released_at=e.completed_at AND a.source_version=4)))
 )) THEN RAISE EXCEPTION 'BFF execution changed after cleanup proof'; END IF;
END \$guard\$;
DELETE FROM inbox_messages WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids)
 OR aggregate_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM quarantined_messages WHERE message_id IN (SELECT message_id FROM e2e_message_ids);
DELETE FROM wash_resource_assignment_read_model WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids)
 AND cabin_id='$CABIN_ID'::uuid AND tank_id='$TANK_ID'::uuid;
DELETE FROM wash_execution_read_model WHERE wash_execution_id IN (SELECT wash_execution_id FROM e2e_execution_ids);
DELETE FROM wash_tank_read_model WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid
 AND code='$TANK_CODE' AND name='$TANK_NAME' AND configured_capacity=1 AND status='ACTIVE' AND source_version=1;
DELETE FROM wash_cabin_read_model WHERE cabin_id='$CABIN_ID'::uuid AND code='$CABIN_CODE'
 AND name='$CABIN_NAME' AND cabin_type='NORMAL' AND status='ACTIVE' AND source_version=1;
DELETE FROM outbox_messages WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DELETE FROM idempotency_records WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
DELETE FROM client_operations WHERE operation_id IN (SELECT operation_id FROM e2e_operation_ids);
COMMIT;
SQL
  [ -z "$terminal_manifest" ] || rm -f -- "$terminal_manifest"
  rm -f -- "$manifest"
  printf 'Synthetic authorized-entry fixture cleaned.\n'
}

case "$mode" in
  bootstrap) bootstrap ;; audit) audit ;; cleanup) cleanup ;;
  *) fail "usage: $0 bootstrap|audit|cleanup" ;;
esac

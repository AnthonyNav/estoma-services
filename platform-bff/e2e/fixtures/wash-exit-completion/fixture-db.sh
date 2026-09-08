#!/bin/sh
set -eu

mode=${1:-}
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
platform_dir=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
report_dir=${E2E_REPORT_DIR:-$platform_dir/target/e2e/wash-exit-completion}
manifest=${E2E_EXIT_FIXTURE_MANIFEST:-$report_dir/exit-terminal-fixture.env}
entry_manifest=${E2E_ENTRY_FIXTURE_MANIFEST:-$report_dir/entry-fixture.env}
authorized_fixture="$script_dir/../wash-supervisor-authorized/fixture-db.sh"
bff_database=${E2E_BFF_DATABASE:-platform_bff_db}
appointments_database=${E2E_APPOINTMENTS_DATABASE:-appointments_db}
execution_database=${E2E_WASH_EXECUTION_DATABASE:-wash_execution_db}

fail() { printf 'wash-exit-completion fixture: %s\n' "$*" >&2; exit 1; }
require_runtime() {
  : "${E2E_PSQL_EXEC:?E2E_PSQL_EXEC must name the isolated database executor}"
  [ -x "$E2E_PSQL_EXEC" ] || fail "E2E_PSQL_EXEC is not executable"
  [ "${E2E_ISOLATED_NAMESPACE:-}" = wash-e2e ] || fail "E2E_ISOLATED_NAMESPACE must be wash-e2e"
  "$E2E_PSQL_EXEC" --assert-isolated-wash >/dev/null || fail "executor did not prove wash isolation"
}
sql() { database=$1; "$E2E_PSQL_EXEC" "$database"; }
value_from() {
  source_manifest=$1 key=$2
  value=$(sed -n "s/^${key}=//p" "$source_manifest")
  [ -n "$value" ] || fail "$source_manifest is missing $key"
  [ "$(printf '%s\n' "$value" | wc -l)" -eq 1 ] || fail "$source_manifest repeats $key"
  printf '%s\n' "$value"
}
require_uuid() {
  case "$1" in ????????-????-????-????-????????????) case "$1" in *[!0-9A-Fa-f-]*) fail "$2 must be a UUID";; esac;; *) fail "$2 must be a UUID";; esac
}
load_entry() {
  [ -f "$entry_manifest" ] || fail "authorized entry manifest does not exist"
  STUDENT_ACCOUNT_ID=$(value_from "$entry_manifest" STUDENT_ACCOUNT_ID)
  SUPERVISOR_ACCOUNT_ID=$(value_from "$entry_manifest" SUPERVISOR_ACCOUNT_ID)
  APPOINTMENT_ID=$(value_from "$entry_manifest" APPOINTMENT_ID)
  SLOT_ID=$(value_from "$entry_manifest" SLOT_ID)
  CABIN_ID=$(value_from "$entry_manifest" CABIN_ID)
  TANK_ID=$(value_from "$entry_manifest" TANK_ID)
  RUN_KEY=$(value_from "$entry_manifest" RUN_KEY)
  case "$RUN_KEY" in e2e-wash-*) ;; *) fail "unsafe run key";; esac
}
load_terminal() {
  [ -f "$manifest" ] || fail "terminal manifest does not exist"
  [ "$(stat -c %a "$manifest")" = 600 ] || fail "terminal manifest must be mode 0600"
  load_entry
  WASH_EXECUTION_ID=$(value_from "$manifest" WASH_EXECUTION_ID)
  RESOURCE_ASSIGNMENT_ID=$(value_from "$manifest" RESOURCE_ASSIGNMENT_ID)
  for key in APPOINTMENT_ID SLOT_ID CABIN_ID TANK_ID STUDENT_ACCOUNT_ID SUPERVISOR_ACCOUNT_ID; do
    terminal_value=$(value_from "$manifest" "$key")
    eval "entry_value=\${$key}"
    [ "$terminal_value" = "$entry_value" ] || fail "$key does not match authorized fixture"
  done
  require_uuid "$WASH_EXECUTION_ID" WASH_EXECUTION_ID
  require_uuid "$RESOURCE_ASSIGNMENT_ID" RESOURCE_ASSIGNMENT_ID
  EXIT_KEY=$(value_from "$manifest" EXIT_KEY)
  COMPLETE_STALE_KEY=$(value_from "$manifest" COMPLETE_STALE_KEY)
  COMPLETE_KEY=$(value_from "$manifest" COMPLETE_KEY)
}

bootstrap() {
  require_runtime; load_entry
  [ ! -e "$manifest" ] || fail "terminal manifest already exists; cleanup first"
  pair=$(sql "$execution_database" <<SQL
SELECT e.wash_execution_id::text || '|' || a.resource_assignment_id
FROM wash_executions e JOIN resource_assignments a ON a.wash_execution_id=e.wash_execution_id
WHERE e.appointment_id='$APPOINTMENT_ID'::uuid AND e.status='IN_PROGRESS' AND e.version=2
 AND a.appointment_time_slot_id='$SLOT_ID'::uuid AND a.cabin_id='$CABIN_ID'::uuid
 AND a.tank_id='$TANK_ID'::uuid AND a.assignment_type='INITIAL' AND a.released_at IS NULL
 AND (SELECT count(*) FROM resource_assignments x WHERE x.wash_execution_id=e.wash_execution_id)=1;
SQL
  )
  old_ifs=$IFS; IFS='|'; set -- $pair; IFS=$old_ifs
  [ "$#" -eq 2 ] || fail "authorized fixture is not one exact IN_PROGRESS execution"
  require_uuid "$1" WASH_EXECUTION_ID; require_uuid "$2" RESOURCE_ASSIGNMENT_ID
  mkdir -p "$report_dir"; chmod 700 "$report_dir"; umask 077
  {
    printf 'WASH_EXECUTION_ID=%s\nRESOURCE_ASSIGNMENT_ID=%s\n' "$1" "$2"
    printf 'APPOINTMENT_ID=%s\nSLOT_ID=%s\nCABIN_ID=%s\nTANK_ID=%s\n' "$APPOINTMENT_ID" "$SLOT_ID" "$CABIN_ID" "$TANK_ID"
    printf 'STUDENT_ACCOUNT_ID=%s\nSUPERVISOR_ACCOUNT_ID=%s\n' "$STUDENT_ACCOUNT_ID" "$SUPERVISOR_ACCOUNT_ID"
    printf 'INVALID_EXIT_KEY=%s-invalid-exit\nFOREIGN_EXIT_KEY=%s-foreign-exit\n' "$RUN_KEY" "$RUN_KEY"
    printf 'EXIT_KEY=%s-exit\nCOMPLETE_STALE_KEY=%s-complete-stale\nCOMPLETE_KEY=%s-complete\n' "$RUN_KEY" "$RUN_KEY" "$RUN_KEY"
    printf 'ORIGINAL_PACKAGE_COUNT=2\nORIGINAL_CASSETTE8_COUNT=1\nORIGINAL_CASSETTE10_COUNT=0\nORIGINAL_WITNESS_COUNT=2\n'
    printf 'FINAL_PACKAGE_COUNT=2\nFINAL_CASSETTE8_COUNT=1\nFINAL_CASSETTE10_COUNT=1\nFINAL_WITNESS_COUNT=2\n'
  } > "$manifest"
  chmod 600 "$manifest"
  printf 'Terminal handoff ready: %s\n' "$manifest"
}

audit() {
  require_runtime; load_terminal
  owner=$(sql "$execution_database" <<SQL
SELECT count(*) FROM wash_executions e JOIN resource_assignments a ON a.resource_assignment_id='$RESOURCE_ASSIGNMENT_ID'::uuid
WHERE e.wash_execution_id='$WASH_EXECUTION_ID'::uuid AND e.appointment_id='$APPOINTMENT_ID'::uuid
 AND e.status='COMPLETED' AND e.version=4 AND e.exit_submitted_by_account_id='$STUDENT_ACCOUNT_ID'::uuid
 AND e.exit_submitted_at IS NOT NULL AND e.completed_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND e.completed_at IS NOT NULL
 AND e.package_count=2 AND e.green_paper_cassette_8_count=1 AND e.green_paper_cassette_10_count=1 AND e.witness_tape_portion_count=2
 AND a.wash_execution_id=e.wash_execution_id AND a.released_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
 AND a.released_at=e.completed_at AND a.release_reason='NORMAL_COMPLETION'
 AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id AND m.message_kind='EVENT')=6
 AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id AND m.message_type='WashExitSubmitted'
   AND m.aggregate_version=3 AND m.idempotency_key='$EXIT_KEY' AND m.status='PUBLISHED'
   AND (m.payload_json->>'packageCount')::int=2 AND (m.payload_json->>'greenPaperCassette8Count')::int=1
   AND (m.payload_json->>'greenPaperCassette10Count')::int=0 AND (m.payload_json->>'witnessTapePortionCount')::int=2)=1
 AND (SELECT count(*) FROM outbox_messages m WHERE m.aggregate_id=e.wash_execution_id AND m.message_type='WashExecutionCompleted'
   AND m.aggregate_version=4 AND m.idempotency_key='$COMPLETE_KEY' AND m.status='PUBLISHED')=1;
SQL
  )
  [ "$owner" = 1 ] || fail "owner terminal history does not match the fixture"
  EXIT_MESSAGE_ID=$(sql "$execution_database" <<SQL
SELECT message_id FROM outbox_messages WHERE aggregate_id='$WASH_EXECUTION_ID'::uuid
 AND message_type='WashExitSubmitted' AND aggregate_version=3 AND idempotency_key='$EXIT_KEY';
SQL
  )
  COMPLETE_MESSAGE_ID=$(sql "$execution_database" <<SQL
SELECT message_id FROM outbox_messages WHERE aggregate_id='$WASH_EXECUTION_ID'::uuid
 AND message_type='WashExecutionCompleted' AND aggregate_version=4 AND idempotency_key='$COMPLETE_KEY';
SQL
  )
  require_uuid "$EXIT_MESSAGE_ID" EXIT_MESSAGE_ID
  require_uuid "$COMPLETE_MESSAGE_ID" COMPLETE_MESSAGE_ID
  bff=$(sql "$bff_database" <<SQL
SELECT count(*) FROM wash_execution_read_model e
JOIN wash_resource_assignment_read_model a ON a.resource_assignment_id='$RESOURCE_ASSIGNMENT_ID'::uuid
WHERE e.wash_execution_id='$WASH_EXECUTION_ID'::uuid AND e.appointment_id='$APPOINTMENT_ID'::uuid
 AND e.status='COMPLETED' AND e.execution_version=4 AND e.submitted_exit_version=3 AND e.final_exit_version=4
 AND e.submitted_package_count=2 AND e.submitted_green_paper_cassette_8_count=1
 AND e.submitted_green_paper_cassette_10_count=0 AND e.submitted_witness_tape_portion_count=2
 AND e.final_package_count=2 AND e.final_green_paper_cassette_8_count=1
 AND e.final_green_paper_cassette_10_count=1 AND e.final_witness_tape_portion_count=2
 AND e.exit_submitted_at IS NOT NULL AND e.completed_at IS NOT NULL AND a.wash_execution_id=e.wash_execution_id
 AND a.cabin_id='$CABIN_ID'::uuid AND a.tank_id='$TANK_ID'::uuid AND a.released_at=e.completed_at AND a.source_version=4
 AND NOT EXISTS (SELECT 1 FROM wash_resource_assignment_read_model active WHERE active.wash_execution_id=e.wash_execution_id AND active.released_at IS NULL)
 AND (SELECT count(*) FROM client_operations WHERE idempotency_key IN ('$EXIT_KEY','$COMPLETE_STALE_KEY','$COMPLETE_KEY'))=3
 AND (SELECT count(*) FROM client_operations WHERE actor_account_id='$STUDENT_ACCOUNT_ID'::uuid AND idempotency_key='$EXIT_KEY' AND status='SUCCEEDED')=1
 AND (SELECT count(*) FROM client_operations WHERE actor_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND idempotency_key='$COMPLETE_STALE_KEY' AND status='REJECTED' AND error_code='VERSION_CONFLICT')=1
 AND (SELECT count(*) FROM client_operations WHERE actor_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND idempotency_key='$COMPLETE_KEY' AND status='SUCCEEDED')=1;
SQL
  )
  [ "$bff" = 1 ] || fail "BFF terminal history or operations are incomplete"
  quarantined=$(sql "$bff_database" <<SQL
SELECT count(*) FROM quarantined_messages WHERE message_id IN
 ('$EXIT_MESSAGE_ID'::uuid,'$COMPLETE_MESSAGE_ID'::uuid);
SQL
  )
  [ "$quarantined" = 0 ] || fail "terminal owner events reached BFF quarantine"
  appointment=$(sql "$appointments_database" <<SQL
SELECT count(*) FROM appointments a WHERE a.appointment_id='$APPOINTMENT_ID'::uuid AND a.status='COMPLETED' AND a.version=4
 AND a.completed_by_account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND a.completed_at IS NOT NULL
 AND (SELECT count(*) FROM wash_execution_transition_projection t WHERE t.appointment_id=a.appointment_id
   AND t.wash_execution_id='$WASH_EXECUTION_ID'::uuid AND t.event_type='WashExecutionCompleted'
   AND t.execution_version=4 AND t.state='COMPLETED')=1;
SQL
  )
  [ "$appointment" = 1 ] || fail "Appointments completion evidence is incomplete"
  pending=$(sql "$bff_database" <<SQL
SELECT count(*) FROM client_operations WHERE idempotency_key IN ('$EXIT_KEY','$COMPLETE_STALE_KEY','$COMPLETE_KEY') AND status='PENDING'
UNION ALL SELECT count(*) FROM outbox_messages WHERE operation_id IN
 (SELECT operation_id FROM client_operations WHERE idempotency_key IN ('$EXIT_KEY','$COMPLETE_STALE_KEY','$COMPLETE_KEY')) AND status<>'PUBLISHED';
SQL
  ); pending=$(printf '%s\n' "$pending" | awk '{n+=$1} END {print n+0}')
  [ "$pending" = 0 ] || fail "terminal messages remain active"
  umask 077
  cp "$manifest" "$report_dir/exit-terminal-audit.env"
  sql "$bff_database" >> "$report_dir/exit-terminal-audit.env" <<SQL
SELECT 'EXIT_OPERATION_ID=' || operation_id FROM client_operations WHERE idempotency_key='$EXIT_KEY'
UNION ALL SELECT 'STALE_COMPLETE_OPERATION_ID=' || operation_id FROM client_operations WHERE idempotency_key='$COMPLETE_STALE_KEY'
UNION ALL SELECT 'COMPLETE_OPERATION_ID=' || operation_id FROM client_operations WHERE idempotency_key='$COMPLETE_KEY';
SQL
  sql "$execution_database" >> "$report_dir/exit-terminal-audit.env" <<SQL
SELECT 'EXIT_MESSAGE_ID=$EXIT_MESSAGE_ID'
UNION ALL SELECT 'COMPLETE_MESSAGE_ID=$COMPLETE_MESSAGE_ID';
SQL
  chmod 600 "$report_dir/exit-terminal-audit.env"
  printf 'Terminal exit/completion audit passed.\n'
}

cleanup() {
  require_runtime; load_terminal
  E2E_AUTHORIZED_TERMINAL_MANIFEST="$manifest" E2E_ENTRY_FIXTURE_MANIFEST="$entry_manifest" \
    E2E_REPORT_DIR="$report_dir" E2E_BFF_DATABASE="$bff_database" \
    E2E_APPOINTMENTS_DATABASE="$appointments_database" E2E_WASH_EXECUTION_DATABASE="$execution_database" \
    "$authorized_fixture" cleanup
}

audit_clean() {
  require_runtime
  [ ! -e "$manifest" ] || fail "terminal manifest still exists"
  [ ! -e "$entry_manifest" ] || fail "entry manifest still exists"
  audit_manifest="$report_dir/exit-terminal-audit.env"
  [ -f "$audit_manifest" ] || fail "terminal audit evidence is missing"
  [ "$(stat -c %a "$audit_manifest")" = 600 ] || fail "terminal audit evidence must be mode 0600"
  WASH_EXECUTION_ID=$(value_from "$audit_manifest" WASH_EXECUTION_ID)
  RESOURCE_ASSIGNMENT_ID=$(value_from "$audit_manifest" RESOURCE_ASSIGNMENT_ID)
  APPOINTMENT_ID=$(value_from "$audit_manifest" APPOINTMENT_ID)
  EXIT_KEY=$(value_from "$audit_manifest" EXIT_KEY)
  COMPLETE_STALE_KEY=$(value_from "$audit_manifest" COMPLETE_STALE_KEY)
  COMPLETE_KEY=$(value_from "$audit_manifest" COMPLETE_KEY)
  EXIT_MESSAGE_ID=$(value_from "$audit_manifest" EXIT_MESSAGE_ID)
  COMPLETE_MESSAGE_ID=$(value_from "$audit_manifest" COMPLETE_MESSAGE_ID)
  execution_rows=$(sql "$execution_database" <<SQL
SELECT
 (SELECT count(*) FROM wash_executions WHERE wash_execution_id='$WASH_EXECUTION_ID'::uuid) +
 (SELECT count(*) FROM resource_assignments WHERE resource_assignment_id='$RESOURCE_ASSIGNMENT_ID'::uuid) +
 (SELECT count(*) FROM outbox_messages WHERE aggregate_id='$WASH_EXECUTION_ID'::uuid OR idempotency_key IN ('$EXIT_KEY','$COMPLETE_STALE_KEY','$COMPLETE_KEY')) +
 (SELECT count(*) FROM command_idempotency_records WHERE producer='bff' AND idempotency_key IN ('$EXIT_KEY','$COMPLETE_STALE_KEY','$COMPLETE_KEY'));
SQL
  )
  [ "$execution_rows" = 0 ] || fail "Wash Execution retained scoped terminal rows"
  appointment_rows=$(sql "$appointments_database" <<SQL
SELECT
 (SELECT count(*) FROM appointments WHERE appointment_id='$APPOINTMENT_ID'::uuid) +
 (SELECT count(*) FROM wash_execution_evidence_projection WHERE wash_execution_id='$WASH_EXECUTION_ID'::uuid) +
 (SELECT count(*) FROM wash_execution_transition_projection WHERE wash_execution_id='$WASH_EXECUTION_ID'::uuid) +
 (SELECT count(*) FROM pending_wash_execution_events WHERE wash_execution_id='$WASH_EXECUTION_ID'::uuid) +
 (SELECT count(*) FROM processed_messages WHERE message_id IN ('$EXIT_MESSAGE_ID'::uuid,'$COMPLETE_MESSAGE_ID'::uuid));
SQL
  )
  [ "$appointment_rows" = 0 ] || fail "Appointments retained scoped terminal rows"
  bff_rows=$(sql "$bff_database" <<SQL
SELECT
 (SELECT count(*) FROM client_operations WHERE idempotency_key IN ('$EXIT_KEY','$COMPLETE_STALE_KEY','$COMPLETE_KEY')) +
 (SELECT count(*) FROM wash_execution_read_model WHERE wash_execution_id='$WASH_EXECUTION_ID'::uuid) +
 (SELECT count(*) FROM wash_resource_assignment_read_model WHERE resource_assignment_id='$RESOURCE_ASSIGNMENT_ID'::uuid) +
 (SELECT count(*) FROM inbox_messages WHERE aggregate_id='$WASH_EXECUTION_ID'::uuid
   OR message_id IN ('$EXIT_MESSAGE_ID'::uuid,'$COMPLETE_MESSAGE_ID'::uuid)) +
 (SELECT count(*) FROM quarantined_messages WHERE message_id IN ('$EXIT_MESSAGE_ID'::uuid,'$COMPLETE_MESSAGE_ID'::uuid));
SQL
  )
  [ "$bff_rows" = 0 ] || fail "BFF retained scoped terminal rows"
  printf 'Terminal scoped absence audit passed across all three stores.\n'
}

case "$mode" in
  bootstrap) bootstrap;; audit) audit;; cleanup) cleanup;; audit-clean) audit_clean;;
  *) fail "usage: $0 bootstrap|audit|cleanup|audit-clean";;
esac

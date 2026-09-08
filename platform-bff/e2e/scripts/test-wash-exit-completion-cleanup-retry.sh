#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
fixture="$script_dir/../fixtures/wash-supervisor-authorized/fixture-db.sh"
terminal_fixture="$script_dir/../fixtures/wash-exit-completion/fixture-db.sh"

mock_sql() {
  database=$1 input=$(cat) state_dir=${E2E_MOCK_STATE_DIR:?}
  case "$database:$input" in
    --assert-isolated-wash:*) exit 0;;
    platform_bff_db:*"status='PENDING'"*) printf '0\n';;
    wash_execution_db:*"status <> 'PUBLISHED'"*) printf '0\n0\n';;
    appointments_db:*"wash_execution_evidence_projection"*"pending_wash_execution_events"*) printf '0\n';;
    appointments_db:*"pending_wash_execution_events"*) printf '0\n0\n';;
    wash_execution_db:*"DELETE FROM resource_assignments"*)
      [ "${E2E_MOCK_CHANGED_AFTER_PROOF:-0}" = 0 ] || exit 1
      : > "$state_dir/owner-cleaned";;
    wash_execution_db:*"SELECT wash_execution_id FROM wash_executions"*)
      [ -f "$state_dir/owner-cleaned" ] || printf '00000000-0000-4000-8000-000000000020\n';;
    wash_execution_db:*"SELECT status || '|' || version FROM wash_executions"*)
      printf '%s\n' "${E2E_MOCK_EXECUTION_STATE:-COMPLETED|4}";;
    platform_bff_db:*"terminal BFF operations"*|platform_bff_db:*"public_operation='wash-submit-exit'"*)
      printf '%s\n' "${E2E_MOCK_OPERATION_PROOF:-0|1|1|1|3}";;
    platform_bff_db:*"execution_version FROM wash_execution_read_model"*)
      printf '%s\n' "${E2E_MOCK_EXECUTION_STATE:-COMPLETED|4}";;
    appointments_db:*"SELECT status || '|' || version FROM appointments"*)
      case "${E2E_MOCK_EXECUTION_STATE:-COMPLETED|4}" in EXIT_SUBMITTED*) printf 'IN_PROGRESS|3\n';; *) printf 'COMPLETED|4\n';; esac;;
    platform_bff_db:*"source_version FROM appointment_read_model"*)
      case "${E2E_MOCK_EXECUTION_STATE:-COMPLETED|4}" in EXIT_SUBMITTED*) printf 'IN_PROGRESS|3\n';; *) printf 'COMPLETED|4\n';; esac;;
    appointments_db:*"wash_execution_transition_projection started"*"started_message.status='APPLIED'"*) printf '1\n';;
    platform_bff_db:*"consumer_name='bff-projection-runtime'"*"WashExecutionCompleted"*)
      case "${E2E_MOCK_EXECUTION_STATE:-COMPLETED|4}" in EXIT_SUBMITTED*) printf '1|1|1|1|1|0\n';; *) printf '1|1|1|1|1|1\n';; esac;;
    wash_execution_db:*"m.message_type='WashExecutionCompleted'"*"command_idempotency_records"*) printf '1\n';;
    wash_execution_db:*"m.message_type='WashExitSubmitted'"*"command_idempotency_records"*) printf '1\n';;
    platform_bff_db:*"DELETE FROM wash_resource_assignment_read_model"*)
      case "$input" in *"submitted_exit_version=3"*"final_exit_version=4"*) ;; *) exit 1;; esac
      : > "$state_dir/bff-cleaned";;
    wash_execution_db:*"Wash Execution retained scoped terminal rows"*|wash_execution_db:*"SELECT"*"command_idempotency_records"*) printf '0\n';;
    platform_bff_db:*"wash_execution_read_model"*"quarantined_messages"*) printf '0\n';;
    *) printf 'unexpected terminal mock SQL for %s:\n%s\n' "$database" "$input" >&2; exit 1;;
  esac
}

case "${1:-}" in
  --assert-isolated-wash) mock_sql "$1"; exit 0;;
  platform_bff_db|appointments_db|wash_execution_db) mock_sql "$1"; exit 0;;
esac

state_dir=$(mktemp -d); trap 'rm -rf -- "$state_dir"' EXIT INT TERM
report_dir="$state_dir/report"; mkdir -p "$report_dir"; chmod 700 "$report_dir"; umask 077
entry="$report_dir/entry-fixture.env"; terminal="$report_dir/exit-terminal-fixture.env"
student="$report_dir/student-fixture.env"; supervisor="$report_dir/supervisor-fixture.env"
: > "$student"; : > "$supervisor"
cat > "$entry" <<'EOF'
SUPERVISOR_ACCOUNT_ID=00000000-0000-4000-8000-000000000001
STUDENT_ACCOUNT_ID=00000000-0000-4000-8000-000000000002
APPOINTMENT_ID=00000000-0000-4000-8000-000000000003
SLOT_ID=00000000-0000-4000-8000-000000000004
CALENDAR_ID=00000000-0000-4000-8000-000000000005
SYSTEM_ID=00000000-0000-4000-8000-000000000006
RUN_KEY=e2e-wash-terminal
REGISTER_KEY=e2e-wash-terminal-register-arrival
DECIDE_KEY=e2e-wash-terminal-decide-entry
ROLE_WAS_ABSENT=1
ORIGINAL_ROLE_SOURCE_VERSION=0
ORIGINAL_ROLE_UPDATED_EPOCH=0
FIXTURE_ROLE_UPDATED_EPOCH=1788600000.123456
CABIN_ID=00000000-0000-4000-8000-000000000007
TANK_ID=00000000-0000-4000-8000-000000000008
CABIN_CODE=E2E-terminal-CABIN
TANK_CODE=E2E-terminal-TANK
CABIN_NAME=E2E-terminal-CABIN
TANK_NAME=E2E-terminal-TANK
EOF
cat > "$terminal" <<'EOF'
WASH_EXECUTION_ID=00000000-0000-4000-8000-000000000020
RESOURCE_ASSIGNMENT_ID=00000000-0000-4000-8000-000000000021
APPOINTMENT_ID=00000000-0000-4000-8000-000000000003
SLOT_ID=00000000-0000-4000-8000-000000000004
CABIN_ID=00000000-0000-4000-8000-000000000007
TANK_ID=00000000-0000-4000-8000-000000000008
STUDENT_ACCOUNT_ID=00000000-0000-4000-8000-000000000002
SUPERVISOR_ACCOUNT_ID=00000000-0000-4000-8000-000000000001
INVALID_EXIT_KEY=e2e-wash-terminal-invalid-exit
FOREIGN_EXIT_KEY=e2e-wash-terminal-foreign-exit
EXIT_KEY=e2e-wash-terminal-exit
COMPLETE_STALE_KEY=e2e-wash-terminal-complete-stale
COMPLETE_KEY=e2e-wash-terminal-complete
ORIGINAL_PACKAGE_COUNT=2
ORIGINAL_CASSETTE8_COUNT=1
ORIGINAL_CASSETTE10_COUNT=0
ORIGINAL_WITNESS_COUNT=2
FINAL_PACKAGE_COUNT=2
FINAL_CASSETTE8_COUNT=1
FINAL_CASSETTE10_COUNT=1
FINAL_WITNESS_COUNT=2
EOF
chmod 600 "$entry" "$terminal"
cp "$entry" "$state_dir/original-entry"; cp "$terminal" "$state_dir/original-terminal"

run_cleanup() {
  E2E_ISOLATED_NAMESPACE=wash-e2e E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$0" \
    E2E_MOCK_STATE_DIR="$state_dir" E2E_ENTRY_FIXTURE_MANIFEST="$entry" \
    E2E_STUDENT_FIXTURE_MANIFEST="$student" E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor" \
    E2E_AUTHORIZED_TERMINAL_MANIFEST="$terminal" "$fixture" cleanup
}
restore() { rm -f "$state_dir/owner-cleaned" "$state_dir/bff-cleaned"; cp "$state_dir/original-entry" "$entry"; cp "$state_dir/original-terminal" "$terminal"; }

if E2E_TEST_FAIL_AFTER_OWNER_CLEANUP=1 run_cleanup >/dev/null 2>&1; then echo 'partial cleanup unexpectedly succeeded' >&2; exit 1; fi
[ -f "$state_dir/owner-cleaned" ] && [ ! -f "$state_dir/bff-cleaned" ] && [ -f "$terminal" ] || { echo 'partial phase was not preserved' >&2; exit 1; }
run_cleanup >/dev/null
[ -f "$state_dir/bff-cleaned" ] && [ ! -e "$terminal" ] && [ ! -e "$entry" ] || { echo 'terminal retry failed' >&2; exit 1; }
cat "$state_dir/original-terminal" > "$report_dir/exit-terminal-audit.env"
printf 'EXIT_MESSAGE_ID=00000000-0000-4000-8000-000000000030\nCOMPLETE_MESSAGE_ID=00000000-0000-4000-8000-000000000031\n' >> "$report_dir/exit-terminal-audit.env"
chmod 600 "$report_dir/exit-terminal-audit.env"
E2E_ISOLATED_NAMESPACE=wash-e2e E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$0" \
  E2E_MOCK_STATE_DIR="$state_dir" E2E_ENTRY_FIXTURE_MANIFEST="$entry" \
  E2E_EXIT_FIXTURE_MANIFEST="$terminal" "$terminal_fixture" audit-clean >/dev/null

restore
if E2E_MOCK_EXECUTION_STATE='CANCELLED|4' run_cleanup >/dev/null 2>&1; then echo 'foreign terminal state accepted' >&2; exit 1; fi
[ ! -f "$state_dir/owner-cleaned" ] && [ -f "$terminal" ] || { echo 'foreign state was mutated' >&2; exit 1; }

if E2E_MOCK_OPERATION_PROOF='0|1|1|2|4' run_cleanup >/dev/null 2>&1; then echo 'ambiguous operations accepted' >&2; exit 1; fi
[ ! -f "$state_dir/owner-cleaned" ] && [ -f "$terminal" ] || { echo 'ambiguous operations were mutated' >&2; exit 1; }

restore
E2E_MOCK_EXECUTION_STATE='EXIT_SUBMITTED|3' E2E_MOCK_OPERATION_PROOF='0|1|1|0|2' run_cleanup >/dev/null
[ -f "$state_dir/owner-cleaned" ] && [ -f "$state_dir/bff-cleaned" ] && [ ! -e "$terminal" ] || { echo 'safe EXIT_SUBMITTED cleanup failed' >&2; exit 1; }

printf 'Wash exit/completion terminal cleanup safety test passed.\n'

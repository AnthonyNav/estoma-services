#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
fixture="$script_dir/../fixtures/wash-supervisor-entry/fixture-db.sh"

mock_sql() {
  database=$1
  input=$(cat)
  state_dir=${E2E_MOCK_STATE_DIR:?E2E_MOCK_STATE_DIR is required}

  case "$database:$input" in
    platform_bff_db:*"FROM client_operations"*"status = 'PENDING'"*) printf '0\n' ;;
    wash_execution_db:*"status IN ('PENDING','PROCESSING')"*) printf '0\n0\n' ;;
    appointments_db:*"FROM outbox_messages"*"pending_wash_execution_events"*) printf '0\n0\n' ;;
    wash_execution_db:*"DELETE FROM wash_executions"*) : > "$state_dir/owner-cleaned" ;;
    platform_bff_db:*"DELETE FROM wash_execution_read_model"*) : > "$state_dir/bff-cleaned" ;;
    wash_execution_db:*"COALESCE((SELECT role_code"*)
      if [ -f "$state_dir/owner-cleaned" ]; then printf '<absent>|0|0\n'; else printf 'SUPERVISOR_LAVADO|1|1\n'; fi
      ;;
    wash_execution_db:*"SELECT wash_execution_id FROM wash_executions"*)
      [ -f "$state_dir/owner-cleaned" ] || printf '00000000-0000-4000-8000-000000000020\n'
      ;;
    wash_execution_db:*"SELECT status || '|' || version::text FROM wash_executions"*) printf 'ENTRY_REJECTED|2\n' ;;
    platform_bff_db:*"SELECT status || '|' || execution_version::text"*) printf 'ENTRY_REJECTED|2\n' ;;
    platform_bff_db:*"SELECT status || '|' || source_version::text"*) printf 'ENTRY_REJECTED|2\n' ;;
    appointments_db:*"SELECT status || '|' || version::text"*) printf 'ENTRY_REJECTED|2\n' ;;
    *) printf 'unexpected mock SQL for %s:\n%s\n' "$database" "$input" >&2; exit 1 ;;
  esac
}

case "${1:-}" in
  platform_bff_db|appointments_db|wash_execution_db)
    mock_sql "$1"
    exit 0
    ;;
esac

state_dir=$(mktemp -d)
report_dir="$state_dir/report"
manifest="$report_dir/entry-fixture.env"
student_manifest="$report_dir/student-fixture.env"
supervisor_manifest="$report_dir/supervisor-fixture.env"
cleanup_test() {
  rm -rf -- "$state_dir"
}
trap cleanup_test EXIT INT TERM
mkdir -p "$report_dir"
chmod 700 "$report_dir"
umask 077
{
  printf '%s\n' \
    'SUPERVISOR_ACCOUNT_ID=00000000-0000-4000-8000-000000000001' \
    'STUDENT_ACCOUNT_ID=00000000-0000-4000-8000-000000000002' \
    'APPOINTMENT_ID=00000000-0000-4000-8000-000000000003' \
    'SLOT_ID=00000000-0000-4000-8000-000000000004' \
    'CALENDAR_ID=00000000-0000-4000-8000-000000000005' \
    'SYSTEM_ID=00000000-0000-4000-8000-000000000006' \
    'RUN_KEY=e2e-wash-entry-retry' \
    'REGISTER_KEY=e2e-wash-entry-retry-register-arrival' \
    'DECIDE_KEY=e2e-wash-entry-retry-decide-entry' \
    'ROLE_WAS_ABSENT=1' \
    'ORIGINAL_ROLE_SOURCE_VERSION=0' \
    'ORIGINAL_ROLE_UPDATED_EPOCH=0'
} > "$manifest"
: > "$student_manifest"
: > "$supervisor_manifest"

common_env() {
  E2E_REPORT_DIR="$report_dir" \
  E2E_ENTRY_FIXTURE_MANIFEST="$manifest" \
  E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" \
  E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" \
  E2E_PSQL_EXEC="$0" \
  E2E_MOCK_STATE_DIR="$state_dir" \
  "$@"
}

if E2E_TEST_FAIL_AFTER_OWNER_CLEANUP=1 common_env "$fixture" cleanup >/dev/null 2>&1; then
  printf 'first cleanup unexpectedly succeeded\n' >&2
  exit 1
fi
[ -f "$state_dir/owner-cleaned" ] || { printf 'owner phase did not commit\n' >&2; exit 1; }
[ ! -f "$state_dir/bff-cleaned" ] || { printf 'BFF phase ran after injected failure\n' >&2; exit 1; }
[ -f "$manifest" ] || { printf 'manifest was removed after partial cleanup\n' >&2; exit 1; }

common_env "$fixture" cleanup >/dev/null
[ -f "$state_dir/bff-cleaned" ] || { printf 'retry did not clean BFF phase\n' >&2; exit 1; }
[ ! -f "$manifest" ] || { printf 'retry did not remove manifest\n' >&2; exit 1; }

printf 'Wash-supervisor rejected-entry cleanup retry test passed.\n'

#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
fixture="$script_dir/../fixtures/wash-supervisor-authorized/fixture-db.sh"

mock_sql() {
  database=$1
  input=$(cat)
  state_dir=${E2E_MOCK_STATE_DIR:?}
  case "$database:$input" in
    --assert-isolated-wash:*) exit 0 ;;
    platform_bff_db:*"FROM client_operations"*"status='PENDING'"*) printf '0\n' ;;
    wash_execution_db:*"status <> 'PUBLISHED'"*) printf '0\n0\n' ;;
    appointments_db:*"pending_wash_execution_events"*) printf '0\n0\n' ;;
    appointments_db:*"entry_authorized_source_event_id"*"WashExecutionStarted"*)
      : > "$state_dir/appointments-consumption-checked"
      printf '%s\n' "${E2E_MOCK_APPOINTMENTS_CONSUMED:-1}" ;;
    appointments_db:*"arrival_source_event_id"*"WashArrivalRegistered"*)
      : > "$state_dir/appointments-consumption-checked"
      printf '%s\n' "${E2E_MOCK_APPOINTMENTS_CONSUMED:-1}" ;;
    platform_bff_db:*"consumer_name='bff-projection-runtime'"*"WashResourceAssigned"*)
      : > "$state_dir/bff-consumption-checked"
      if [ "${E2E_MOCK_BFF_CONSUMED:-1}" = 0 ]; then printf '0|0|0|0\n'
      elif [ "${E2E_MOCK_EXECUTION_STATE:-}" = 'PENDING_ENTRY|1' ]; then printf '1|0|0|0\n'
      else printf '1|1|1|1\n'; fi ;;
    wash_execution_db:*"DELETE FROM resource_assignments"*)
      case "$input" in *"LOCK TABLE wash_executions, entry_validations, resource_assignments"*) ;; *) exit 1 ;; esac
      case "$input" in *"cabin_id='00000000-0000-4000-8000-000000000007'::uuid OR code='E2E-authorized-retry-CABIN'"*) ;; *) exit 1 ;; esac
      case "$input" in *"execution changed after cleanup proof"*) ;; *) exit 1 ;; esac
      case "$input" in *"message_type='WashExecutionStarted'"*"idempotency_key='e2e-wash-authorized-retry-decide-entry'"*) ;; *) exit 1 ;; esac
      [ "${E2E_MOCK_ALTERED_RESOURCE:-0}" = 0 ] || exit 1
      [ "${E2E_MOCK_CHANGED_AFTER_PROOF:-0}" = 0 ] || exit 1
      : > "$state_dir/owner-cleaned"
      ;;
    platform_bff_db:*"DELETE FROM wash_resource_assignment_read_model"*)
      case "$input" in *"LOCK TABLE client_operations, inbox_messages"*) ;; *) exit 1 ;; esac
      case "$input" in *"cabin_id='00000000-0000-4000-8000-000000000007'::uuid OR code='E2E-authorized-retry-CABIN'"*) ;; *) exit 1 ;; esac
      case "$input" in *"BFF execution changed after cleanup proof"*) ;; *) exit 1 ;; esac
      case "$input" in *"a.source_version=2"*) ;; *) exit 1 ;; esac
      [ "${E2E_MOCK_ALTERED_BFF_RESOURCE:-0}" = 0 ] || exit 1
      : > "$state_dir/bff-cleaned"
      ;;
    wash_execution_db:*"SELECT wash_execution_id FROM wash_executions"*)
      [ -f "$state_dir/owner-cleaned" ] || printf '00000000-0000-4000-8000-000000000020\n' ;;
    wash_execution_db:*"SELECT status || '|' || version FROM wash_executions"*)
      printf '%s\n' "${E2E_MOCK_EXECUTION_STATE:-IN_PROGRESS|2}" ;;
    wash_execution_db:*"JOIN entry_validations"*) printf '1\n' ;;
    wash_execution_db:*"AND e.started_at IS NULL"*) printf '1\n' ;;
    platform_bff_db:*"execution_version FROM wash_execution_read_model"*)
      printf '%s\n' "${E2E_MOCK_EXECUTION_STATE:-IN_PROGRESS|2}" ;;
    appointments_db:*"SELECT status || '|' || version FROM appointments"*)
      if [ "${E2E_MOCK_EXECUTION_STATE:-}" = 'PENDING_ENTRY|1' ]; then printf 'SCHEDULED|1\n'; else printf 'IN_PROGRESS|3\n'; fi ;;
    platform_bff_db:*"source_version FROM appointment_read_model"*)
      if [ "${E2E_MOCK_EXECUTION_STATE:-}" = 'PENDING_ENTRY|1' ]; then printf 'SCHEDULED|1\n'; else printf 'IN_PROGRESS|3\n'; fi ;;
    *) printf 'unexpected mock SQL for %s:\n%s\n' "$database" "$input" >&2; exit 1 ;;
  esac
}

case "${1:-}" in
  --assert-isolated-wash) mock_sql "$1"; exit 0 ;;
  platform_bff_db|appointments_db|wash_execution_db) mock_sql "$1"; exit 0 ;;
esac

state_dir=$(mktemp -d)
report_dir="$state_dir/report"
manifest="$report_dir/entry-fixture.env"
student_manifest="$report_dir/student-fixture.env"
supervisor_manifest="$report_dir/supervisor-fixture.env"
trap 'rm -rf -- "$state_dir"' EXIT INT TERM
mkdir -p "$report_dir"
chmod 700 "$report_dir"
umask 077
printf '%s\n' \
  'SUPERVISOR_ACCOUNT_ID=00000000-0000-4000-8000-000000000001' \
  'STUDENT_ACCOUNT_ID=00000000-0000-4000-8000-000000000002' \
  'APPOINTMENT_ID=00000000-0000-4000-8000-000000000003' \
  'SLOT_ID=00000000-0000-4000-8000-000000000004' \
  'CALENDAR_ID=00000000-0000-4000-8000-000000000005' \
  'SYSTEM_ID=00000000-0000-4000-8000-000000000006' \
  'RUN_KEY=e2e-wash-authorized-retry' \
  'REGISTER_KEY=e2e-wash-authorized-retry-register-arrival' \
  'DECIDE_KEY=e2e-wash-authorized-retry-decide-entry' \
  'ROLE_WAS_ABSENT=1' \
  'ORIGINAL_ROLE_SOURCE_VERSION=0' \
  'ORIGINAL_ROLE_UPDATED_EPOCH=0' \
  'FIXTURE_ROLE_UPDATED_EPOCH=1788600000.123456' \
  'CABIN_ID=00000000-0000-4000-8000-000000000007' \
  'TANK_ID=00000000-0000-4000-8000-000000000008' \
  'CABIN_CODE=E2E-authorized-retry-CABIN' \
  'TANK_CODE=E2E-authorized-retry-TANK' \
  'CABIN_NAME=E2E-authorized-retry-CABIN' \
  'TANK_NAME=E2E-authorized-retry-TANK' > "$manifest"
: > "$student_manifest"; : > "$supervisor_manifest"
cp "$manifest" "$state_dir/original-manifest"

run_cleanup() {
  E2E_ISOLATED_NAMESPACE=wash-e2e E2E_REPORT_DIR="$report_dir" \
  E2E_ENTRY_FIXTURE_MANIFEST="$manifest" E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" \
  E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" E2E_PSQL_EXEC="$0" \
  E2E_MOCK_STATE_DIR="$state_dir" "$fixture" cleanup
}

if E2E_TEST_FAIL_AFTER_OWNER_CLEANUP=1 run_cleanup >/dev/null 2>&1; then
  printf 'first cleanup unexpectedly succeeded\n' >&2; exit 1
fi
[ -f "$state_dir/owner-cleaned" ] && [ ! -f "$state_dir/bff-cleaned" ] && [ -f "$manifest" ] || {
  printf 'owner phase was not safely retryable\n' >&2; exit 1;
}
[ -f "$state_dir/appointments-consumption-checked" ] && [ -f "$state_dir/bff-consumption-checked" ] || {
  printf 'cleanup skipped a downstream source-event consumption barrier\n' >&2; exit 1;
}
run_cleanup >/dev/null
[ -f "$state_dir/bff-cleaned" ] && [ ! -f "$manifest" ] || { printf 'retry failed\n' >&2; exit 1; }

rm -f "$state_dir/owner-cleaned" "$state_dir/bff-cleaned"
cp "$state_dir/original-manifest" "$manifest"
E2E_MOCK_EXECUTION_STATE='PENDING_ENTRY|1' run_cleanup >/dev/null
[ -f "$state_dir/owner-cleaned" ] && [ -f "$state_dir/bff-cleaned" ] && [ ! -f "$manifest" ] || {
  printf 'interrupted arrival cleanup failed\n' >&2; exit 1;
}

rm -f "$state_dir/owner-cleaned" "$state_dir/bff-cleaned"
cp "$state_dir/original-manifest" "$manifest"
if E2E_MOCK_EXECUTION_STATE='ENTRY_REJECTED|2' run_cleanup >/dev/null 2>&1; then
  printf 'cleanup accepted an execution outside the authorized path\n' >&2; exit 1
fi
[ ! -f "$state_dir/owner-cleaned" ] && [ -f "$manifest" ] || {
  printf 'unsafe execution was changed or lost its manifest\n' >&2; exit 1;
}

rm -f "$state_dir/owner-cleaned" "$state_dir/bff-cleaned"
cp "$state_dir/original-manifest" "$manifest"
if E2E_MOCK_ALTERED_RESOURCE=1 run_cleanup >/dev/null 2>&1; then
  printf 'cleanup accepted an altered owner resource\n' >&2; exit 1
fi
[ ! -f "$state_dir/owner-cleaned" ] && [ -f "$manifest" ] || {
  printf 'altered owner resource was deleted or lost its manifest\n' >&2; exit 1;
}

if E2E_MOCK_CHANGED_AFTER_PROOF=1 run_cleanup >/dev/null 2>&1; then
  printf 'cleanup accepted an execution changed after its read proof\n' >&2; exit 1
fi
[ ! -f "$state_dir/owner-cleaned" ] && [ -f "$manifest" ] || {
  printf 'concurrently changed execution was deleted or lost its manifest\n' >&2; exit 1;
}

if E2E_MOCK_ALTERED_BFF_RESOURCE=1 run_cleanup >/dev/null 2>&1; then
  printf 'cleanup accepted an altered BFF resource\n' >&2; exit 1
fi
[ -f "$state_dir/owner-cleaned" ] && [ ! -f "$state_dir/bff-cleaned" ] && [ -f "$manifest" ] || {
  printf 'altered BFF resource did not preserve retry state\n' >&2; exit 1;
}

if E2E_ISOLATED_NAMESPACE=production E2E_ENTRY_FIXTURE_MANIFEST="$manifest" \
  E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" \
  E2E_PSQL_EXEC="$0" E2E_MOCK_STATE_DIR="$state_dir" "$fixture" cleanup >/dev/null 2>&1; then
  printf 'cleanup accepted a non-isolated namespace\n' >&2; exit 1
fi
printf 'Wash-supervisor authorized-entry cleanup retry test passed.\n'

#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
fixture="$script_dir/../fixtures/wash-student/fixture-db.sh"

mock_sql() {
  database=$1
  input=$(cat)
  state_dir=${E2E_MOCK_STATE_DIR:?E2E_MOCK_STATE_DIR is required}

  case "$database:$input" in
    platform_bff_db:*"FROM account_presentation_read_model"*)
      printf 'yes\n'
      ;;
    platform_bff_db:*"FROM client_operations"*"UNION ALL"*)
      printf '0\n0\n'
      ;;
    appointments_db:*"status <> 'PUBLISHED'"*)
      printf '0\n'
      ;;
    appointments_db:*"SELECT status || '|' || version FROM appointments"*)
      if [ ! -f "$state_dir/owner-cleaned" ]; then
        printf 'CANCELLED|2\n'
      fi
      ;;
    platform_bff_db:*"SELECT status || '|' || source_version FROM appointment_read_model"*)
      printf 'CANCELLED|2\n'
      ;;
    appointments_db:*"DELETE FROM appointments WHERE"*)
      : > "$state_dir/owner-cleaned"
      ;;
    platform_bff_db:*"DELETE FROM appointment_read_model WHERE"*)
      : > "$state_dir/bff-cleaned"
      ;;
    *)
      printf 'unexpected mock SQL for %s\n' "$database" >&2
      exit 1
      ;;
  esac
}

case "${1:-}" in
  platform_bff_db|appointments_db)
    mock_sql "$1"
    exit 0
    ;;
esac

state_dir=$(mktemp -d)
report_dir="$state_dir/report"
manifest="$report_dir/fixture.env"
cleanup_test() {
  rm -rf -- "$state_dir"
}
trap cleanup_test EXIT INT TERM
mkdir -p "$report_dir"
chmod 700 "$report_dir"

umask 077
{
  printf '%s\n' \
    'ACCOUNT_ID=00000000-0000-4000-8000-000000000001' \
    'LOGIN_IDENTIFIER=e2e-cleanup-retry' \
    'PERSON_ID=00000000-0000-4000-8000-000000000002' \
    'ACADEMIC_PROFILE_ID=00000000-0000-4000-8000-000000000003' \
    'COURSE_SECTION_ID=00000000-0000-4000-8000-000000000004' \
    'ROLE_ID=00000000-0000-4000-8000-000000000005' \
    'SYSTEM_ACCESS_ID=00000000-0000-4000-8000-000000000006' \
    'SYSTEM_ID=00000000-0000-4000-8000-000000000007' \
    'PERIOD_ID=00000000-0000-4000-8000-000000000008' \
    'PERIOD_WAS_SYNTHETIC=1' \
    'SLOT_ID=00000000-0000-4000-8000-000000000009' \
    'SCHEDULE_ID=00000000-0000-4000-8000-000000000010' \
    'CALENDAR_ID=00000000-0000-4000-8000-000000000011' \
    'RUN_KEY=e2e-wash-cleanup-retry' \
    'NRC=E2E-cleanupretry' \
    'BFF_ROLE_WAS_ABSENT=1' \
    'OWNER_ROLE_WAS_ABSENT=1'
} > "$manifest"

if E2E_REPORT_DIR="$report_dir" \
    E2E_FIXTURE_MANIFEST="$manifest" \
    E2E_PSQL_EXEC="$0" \
    E2E_MOCK_STATE_DIR="$state_dir" \
    E2E_TEST_FAIL_AFTER_OWNER_CLEANUP=1 \
    "$fixture" cleanup >/dev/null 2>&1; then
  printf 'first cleanup unexpectedly succeeded\n' >&2
  exit 1
fi

[ -f "$state_dir/owner-cleaned" ] || { printf 'owner phase did not commit\n' >&2; exit 1; }
[ ! -f "$state_dir/bff-cleaned" ] || { printf 'BFF phase ran after injected failure\n' >&2; exit 1; }
[ -f "$manifest" ] || { printf 'manifest was removed after partial cleanup\n' >&2; exit 1; }

E2E_REPORT_DIR="$report_dir" \
E2E_FIXTURE_MANIFEST="$manifest" \
E2E_PSQL_EXEC="$0" \
E2E_MOCK_STATE_DIR="$state_dir" \
"$fixture" cleanup >/dev/null

[ -f "$state_dir/bff-cleaned" ] || { printf 'retry did not clean BFF phase\n' >&2; exit 1; }
[ ! -f "$manifest" ] || { printf 'retry did not remove manifest\n' >&2; exit 1; }

printf 'Wash-student cleanup retry test passed.\n'

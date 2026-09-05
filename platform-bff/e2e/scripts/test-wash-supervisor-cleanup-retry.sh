#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
fixture="$script_dir/../fixtures/wash-supervisor/fixture-db.sh"

mock_sql() {
  database=$1
  input=$(cat)
  state_dir=${E2E_MOCK_STATE_DIR:?E2E_MOCK_STATE_DIR is required}
  [ "$database" = platform_bff_db ] || { printf 'unexpected database %s\n' "$database" >&2; exit 1; }

  case "$input" in
    *"FROM account_presentation_read_model"*) printf 'yes\n' ;;
    *"THEN ("*"extract(epoch FROM updated_at)"*) printf 'E2E_TESTER|7|1700000000.000000\n' ;;
    *"COALESCE((SELECT role_code"*"authorization_access_projection"*)
      if [ -f "$state_dir/restored" ]; then printf 'E2E_TESTER|0\n'; else printf 'SUPERVISOR_LAVADO|1\n'; fi
      ;;
    *"SELECT count(*) FROM authorization_access_projection"*) printf '0\n' ;;
    *"SELECT count(*) FROM projection_status"*) printf '7\n' ;;
    *"FROM authorization_system_catalog_read_model"*) printf '00000000-0000-4000-8000-000000000007\n' ;;
    *"SELECT count(*) FROM client_operations"*) printf '3\n' ;;
    *"SELECT count(*)"*"FROM wash_execution_read_model"*) printf '0\n' ;;
    *"SELECT COALESCE(role_source_version::text"*) printf '7|1700000000.000000\n' ;;
    *"INSERT INTO authorization_role_projection"*) : > "$state_dir/applied" ;;
    *"DELETE FROM authorization_access_projection"*) : > "$state_dir/restored" ;;
    *) printf 'unexpected mock SQL:\n%s\n' "$input" >&2; exit 1 ;;
  esac
}

case "${1:-}" in
  platform_bff_db)
    mock_sql "$1"
    exit 0
    ;;
esac

state_dir=$(mktemp -d)
report_dir="$state_dir/report"
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
    'ACCOUNT_ID=00000000-0000-4000-8000-000000000001' \
    'SLOT_ID=00000000-0000-4000-8000-000000000002' \
    'RUN_KEY=e2e-wash-supervisor-retry'
} > "$student_manifest"

common_env() {
  E2E_REPORT_DIR="$report_dir" \
  E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" \
  E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" \
  E2E_PSQL_EXEC="$0" \
  E2E_MOCK_STATE_DIR="$state_dir" \
  "$@"
}

E2E_SUPERVISOR_ACCOUNT_ID=00000000-0000-4000-8000-000000000003 \
E2E_SUPERVISOR_LOGIN_IDENTIFIER=e2e-supervisor-retry \
common_env "$fixture" bootstrap >/dev/null
[ -f "$state_dir/applied" ] || { printf 'bootstrap did not apply authorization\n' >&2; exit 1; }
[ -f "$supervisor_manifest" ] || { printf 'bootstrap did not preserve its manifest\n' >&2; exit 1; }

common_env "$fixture" audit >/dev/null

if E2E_TEST_FAIL_AFTER_DB_CLEANUP=1 common_env "$fixture" cleanup >/dev/null 2>&1; then
  printf 'first cleanup unexpectedly succeeded\n' >&2
  exit 1
fi
[ -f "$state_dir/restored" ] || { printf 'cleanup transaction did not restore authorization\n' >&2; exit 1; }
[ -f "$supervisor_manifest" ] || { printf 'manifest was removed after injected failure\n' >&2; exit 1; }

common_env "$fixture" cleanup >/dev/null
[ ! -f "$supervisor_manifest" ] || { printf 'retry did not remove completed manifest\n' >&2; exit 1; }

printf 'Wash-supervisor cleanup retry test passed.\n'

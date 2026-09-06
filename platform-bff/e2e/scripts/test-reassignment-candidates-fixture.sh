#!/bin/sh
set -eu

case "$(basename "$0")" in
  sleep) exit 0 ;;
  fake-psql)
    [ "$1" = --assert-isolated-wash ] && exit 0
    input=$(sed -n '1,999p')
    printf '%s\n' "DB=$1" "$input" >> "$TEST_SQL_LOG"
    case "$input" in
      *"SELECT system_id FROM authorization_system_catalog_read_model"*) printf '%s\n' '00000000-0000-4000-8000-000000000010' ;;
      *"SELECT (SELECT count(*) FROM projection_status"*) printf '%s\n' '2|0|0|0|0|0|0|0' ;;
      *"SELECT (SELECT count(*) FROM projection_recovery_state"*) printf '%s\n' '2|0|0|0|0|0' ;;
      *"UPDATE tank_projection SET status='INACTIVE'"*) printf '1\n' ;;
      *"string_agg(query_message_id::text"*)
        [ "${TEST_V15_GONE:-0}" = 1 ] || printf '00000000-0000-4000-8000-000000000020\n' ;;
      *"SELECT"*"reassignment_candidates_refresh WHERE wash_execution_id"*"authorization_access_projection"*) printf '0\n' ;;
      *"SELECT"*"outbox_messages WHERE causation_id=ANY"*"((SELECT count(*) FROM wash_executions"*) printf '1|5\n' ;;
      *"SELECT"*"outbox_messages WHERE causation_id=ANY"*"tank_projection WHERE tank_id"*) printf '0\n' ;;
      *"SELECT message_id::text || '|' || correlation_id::text FROM outbox_messages"*)
        if [ "${TEST_IN_FLIGHT:-0}" != 1 ]; then printf '00000000-0000-4000-8000-000000000021|00000000-0000-4000-8000-000000000022\n'; fi ;;
      *"consumer_name='bff-reassignment-candidates-results'"*) printf '1|1\n' ;;
      *"reassignment_candidates_refresh WHERE wash_execution_id"*"reassignment_candidates_refresh_history"*)
        if [ "${TEST_V15_GONE:-0}" = 1 ]; then printf '0\n'; else printf '1\n'; fi ;;
    esac
    exit 0 ;;
esac

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
fixture="$script_dir/../fixtures/reassignment-candidates/fixture-db.sh"
root=$(mktemp -d)
trap 'rm -rf -- "$root"' EXIT INT TERM
mkdir -p "$root/bin" "$root/report"
cp "$0" "$root/bin/fake-psql"; cp "$0" "$root/bin/sleep"; chmod +x "$root/bin/fake-psql" "$root/bin/sleep"
printf '%s\n' \
  'STUDENT_ACCOUNT_ID=00000000-0000-4000-8000-000000000001' \
  'SUPERVISOR_ACCOUNT_ID=00000000-0000-4000-8000-000000000002' > "$root/report/identity-fixture.env"
chmod 600 "$root/report/identity-fixture.env"
export TEST_SQL_LOG="$root/sql.log" E2E_REPORT_DIR="$root/report" E2E_PSQL_EXEC="$root/bin/fake-psql"
PATH="$root/bin:$PATH" "$fixture" bootstrap >/dev/null
manifest="$root/report/reassignment-candidates.env"
[ -f "$manifest" ]
grep -q "INSERT INTO wash_executions" "$TEST_SQL_LOG"
grep -q "INSERT INTO wash_execution_read_model" "$TEST_SQL_LOG"
grep -q "reassignment_candidates_refresh_history" "$TEST_SQL_LOG"
PATH="$root/bin:$PATH" "$fixture" deactivate-tank >/dev/null

if TEST_IN_FLIGHT=1 E2E_CANDIDATE_CLEANUP_ATTEMPTS=1 PATH="$root/bin:$PATH" "$fixture" cleanup >"$root/refused" 2>&1; then
  printf 'cleanup accepted incomplete owner Query/Result evidence\n' >&2; exit 1
fi
[ -f "$manifest" ] || { printf 'failed cleanup removed its retry manifest\n' >&2; exit 1; }
grep -q 'cleanup refused while tracked Query/Result work is in flight' "$root/refused"
grep -q "DELETE FROM wash_execution_read_model" "$TEST_SQL_LOG"
grep -q "LOCK TABLE wash_execution_read_model IN ACCESS EXCLUSIVE MODE" "$TEST_SQL_LOG"
if grep -q "DELETE FROM wash_executions WHERE" "$TEST_SQL_LOG"; then
  printf 'failed cleanup reached destructive owner deletion\n' >&2; exit 1
fi

if E2E_TEST_FAIL_AFTER_BFF_CLEANUP=1 PATH="$root/bin:$PATH" "$fixture" cleanup >"$root/retry" 2>&1; then
  printf 'injected post-BFF cleanup failure unexpectedly succeeded\n' >&2; exit 1
fi
if [ "$(sed -n 's/^TRACKED_QUERY_IDS=//p' "$manifest")" != '00000000-0000-4000-8000-000000000020' ]; then
  sed -n '1,120p' "$root/retry" >&2; exit 1
fi
TEST_V15_GONE=1 PATH="$root/bin:$PATH" "$fixture" cleanup >/dev/null
[ ! -e "$manifest" ]
grep -q "not every tracked Result reached the BFF inbox" "$TEST_SQL_LOG"
grep -q "owner fixture or tracked Result state no longer matches the manifest" "$TEST_SQL_LOG"
grep -q "DELETE FROM wash_executions WHERE" "$TEST_SQL_LOG"
printf 'Reassignment candidates fixture guards, fence ordering and fail-closed cleanup passed.\n'

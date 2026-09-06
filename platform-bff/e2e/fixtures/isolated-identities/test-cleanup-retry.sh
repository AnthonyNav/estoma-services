#!/bin/sh
set -eu

fixture=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/fixture-db.sh
test_root=$(mktemp -d)
trap 'rm -rf -- "$test_root"' EXIT INT TERM
report="$test_root/report"
mkdir -p "$report"
chmod 700 "$report"
umask 077

cat > "$report/identity-fixture.env" <<'EOF'
STUDENT_ACCOUNT_ID=11111111-1111-4111-8111-111111111111
STUDENT_PERSON_ID=22222222-2222-4222-8222-222222222222
STUDENT_LOGIN=e2e-student-retry
SUPERVISOR_ACCOUNT_ID=33333333-3333-4333-8333-333333333333
SUPERVISOR_PERSON_ID=44444444-4444-4444-8444-444444444444
SUPERVISOR_LOGIN=e2e-supervisor-retry
EOF
cat > "$report/identity-credentials.json" <<'EOF'
{"studentAccountId":"11111111-1111-4111-8111-111111111111","studentPersonId":"22222222-2222-4222-8222-222222222222","studentLogin":"e2e-student-retry","studentPassword":"private-student","supervisorAccountId":"33333333-3333-4333-8333-333333333333","supervisorPersonId":"44444444-4444-4444-8444-444444444444","supervisorLogin":"e2e-supervisor-retry","supervisorPassword":"private-supervisor"}
EOF
chmod 600 "$report/identity-fixture.env" "$report/identity-credentials.json"

cat > "$test_root/psql" <<'EOF'
#!/bin/sh
set -eu
database=$1
if [ "$database" = --assert-isolated-wash ]; then
  printf 'ASSERT\n' >> "$E2E_TEST_CALLS"
  exit 0
fi
query=$(cat)
case "$database:$query" in
  authentication_db:*string_agg*)
    printf "'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'\n"
    ;;
  authentication_db:*"BEGIN;"*)
    printf 'AUTH_MUTATION\n' >> "$E2E_TEST_CALLS"
    ;;
  authentication_db:*"password_recovery_requests"*)
    if [ "${E2E_TEST_ACTIVE:-0}" = 1 ]; then printf '1\n'; else printf '0\n'; fi
    ;;
  authentication_db:*)
    printf '0\n'
    ;;
  platform_bff_db:*"BEGIN;"*)
    printf 'BFF_MUTATION\n' >> "$E2E_TEST_CALLS"
    printf '%s\n' "$query" >> "$E2E_TEST_BFF_SQL"
    ;;
  platform_bff_db:*)
    printf '0\n'
    ;;
esac
EOF
chmod +x "$test_root/psql"
: > "$test_root/calls"
: > "$test_root/bff-sql"

common_run() {
  E2E_REPORT_DIR="$report" E2E_PSQL_EXEC="$test_root/psql" \
    E2E_TEST_CALLS="$test_root/calls" E2E_TEST_BFF_SQL="$test_root/bff-sql" "$@"
}

if common_run env E2E_TEST_ACTIVE=1 "$fixture" cleanup >/dev/null 2>&1; then
  echo 'active-session cleanup unexpectedly succeeded' >&2
  exit 1
fi
! grep -q BFF_MUTATION "$test_root/calls"
! grep -q ASSERT "$test_root/calls"

: > "$test_root/calls"
if common_run env E2E_IDENTITY_INJECT_FAILURE_AFTER_BFF_CLEANUP=1 "$fixture" cleanup >/dev/null 2>&1; then
  echo 'injected cleanup unexpectedly succeeded' >&2
  exit 1
fi
[ "$(grep -c BFF_MUTATION "$test_root/calls")" -eq 1 ]
! grep -q AUTH_MUTATION "$test_root/calls"
[ -f "$report/identity-fixture.env" ]
[ -f "$report/identity-credentials.json" ]

common_run "$fixture" cleanup >/dev/null
[ "$(grep -c BFF_MUTATION "$test_root/calls")" -eq 2 ]
[ "$(grep -c AUTH_MUTATION "$test_root/calls")" -eq 1 ]
[ ! -e "$report/identity-fixture.env" ]
[ ! -e "$report/identity-credentials.json" ]
grep -q 'account_rows=0 AND person_rows=0' "$test_root/bff-sql"
printf 'isolated identity cleanup retry test passed\n'

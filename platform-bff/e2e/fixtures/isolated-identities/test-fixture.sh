#!/bin/sh
set -eu

fixture=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/fixture-db.sh
test_root=$(mktemp -d)
trap 'rm -rf -- "$test_root"' EXIT INT TERM

cat > "$test_root/psql" <<'EOF'
#!/bin/sh
set -eu
printf '%s\n' "$1" >> "$E2E_TEST_CALLS"
if [ "$1" != --assert-isolated-wash ]; then cat >> "$E2E_TEST_SQL"; fi
EOF
cat > "$test_root/hash" <<'EOF'
#!/bin/sh
set -eu
IFS= read -r password
[ -n "$password" ]
printf '$argon2id$v=19$m=19456,t=2,p=1$synthetic$synthetic\n'
EOF
chmod +x "$test_root/psql" "$test_root/hash"
: > "$test_root/calls"
: > "$test_root/sql"

E2E_REPORT_DIR="$test_root/report" \
E2E_PSQL_EXEC="$test_root/psql" \
E2E_PASSWORD_HASH_EXEC="$test_root/hash" \
E2E_TEST_CALLS="$test_root/calls" \
E2E_TEST_SQL="$test_root/sql" \
  "$fixture" bootstrap >/dev/null

[ "$(stat -c %a "$test_root/report/identity-fixture.env")" = 600 ]
[ "$(stat -c %a "$test_root/report/identity-credentials.json")" = 600 ]
node -e 'const d=require(process.argv[1]); for (const k of ["studentAccountId","studentPersonId","studentLogin","studentPassword","supervisorAccountId","supervisorPersonId","supervisorLogin","supervisorPassword"]) if (!d[k]) process.exit(1)' "$test_root/report/identity-credentials.json"
! grep -q PASSWORD "$test_root/report/identity-fixture.env"
[ "$(grep -c '^--assert-isolated-wash$' "$test_root/calls")" -eq 2 ]
[ "$(grep -c '^authentication_db$' "$test_root/calls")" -eq 1 ]
[ "$(grep -c '^platform_bff_db$' "$test_root/calls")" -eq 1 ]
grep -q 'INSERT INTO accounts' "$test_root/sql"
grep -q 'INSERT INTO account_presentation_read_model' "$test_root/sql"
printf 'isolated identity fixture bootstrap test passed\n'

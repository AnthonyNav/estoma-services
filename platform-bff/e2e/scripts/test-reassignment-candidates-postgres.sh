#!/bin/sh
set -eu

case "$(basename "$0")" in
  fixture-psql)
    [ "$1" = --assert-isolated-wash ] && exit 0
    exec docker exec -i "$TEST_PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U postgres -Atq -d "$1"
    ;;
esac

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
platform_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
fixture="$platform_dir/e2e/fixtures/reassignment-candidates/fixture-db.sh"
test_root=$(mktemp -d)
container="candidate-schema-$$"
cleanup_test() { docker rm -f "$container" >/dev/null 2>&1 || true; rm -rf -- "$test_root"; }
trap cleanup_test EXIT INT TERM
cp "$0" "$test_root/fixture-psql"; chmod +x "$test_root/fixture-psql"
export TEST_PG_CONTAINER="$container"
docker run -d --rm --name "$container" -e POSTGRES_PASSWORD=fixture postgres:16-alpine >/dev/null
attempt=0
until docker exec "$container" pg_isready -U postgres >/dev/null 2>&1; do
  attempt=$((attempt+1)); [ "$attempt" -lt 30 ] || { printf 'local PostgreSQL did not become ready\n' >&2; exit 1; }
  sleep 1
done
docker exec "$container" createdb -U postgres platform_bff_db
docker exec "$container" createdb -U postgres wash_execution_db
find "$platform_dir/src/main/resources/db/migration" -type f -name 'V*.sql' | sort -V | while IFS= read -r migration; do
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d platform_bff_db < "$migration" >/dev/null
done
find "$platform_dir/../wash-execution-service/src/main/resources/db/migration" -type f -name 'V*.sql' | sort -V | while IFS= read -r migration; do
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d wash_execution_db < "$migration" >/dev/null
done
docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d platform_bff_db >/dev/null <<'SQL'
UPDATE projection_status SET status='READY' WHERE projection_name IN ('authorization','wash-execution');
INSERT INTO authorization_system_catalog_read_model(system_id,system_code,status,source_version,trusted,updated_at)
VALUES('00000000-0000-4000-8000-000000000010','LAVADO_ULTRASONICO','ACTIVE',1,true,CURRENT_TIMESTAMP);
SQL
docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d wash_execution_db >/dev/null <<'SQL'
INSERT INTO projection_recovery_state(projection_name,source_logical_component_id,readiness_status,updated_at)
VALUES('appointments','appointments','READY',CURRENT_TIMESTAMP),
 ('wash-administration','wash-administration','READY',CURRENT_TIMESTAMP);
SQL
mkdir -p "$test_root/report"
printf '%s\n' \
  'STUDENT_ACCOUNT_ID=00000000-0000-4000-8000-000000000001' \
  'SUPERVISOR_ACCOUNT_ID=00000000-0000-4000-8000-000000000002' > "$test_root/report/identity-fixture.env"
chmod 600 "$test_root/report/identity-fixture.env"
E2E_REPORT_DIR="$test_root/report" E2E_PSQL_EXEC="$test_root/fixture-psql" "$fixture" bootstrap >/dev/null
E2E_REPORT_DIR="$test_root/report" E2E_PSQL_EXEC="$test_root/fixture-psql" "$fixture" deactivate-tank >/dev/null
EXECUTION_ID=$(sed -n 's/^EXECUTION_ID=//p' "$test_root/report/reassignment-candidates.env")
query_id=00000000-0000-4000-8000-000000000020
result_id=00000000-0000-4000-8000-000000000021
correlation_id=00000000-0000-4000-8000-000000000022
docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d wash_execution_db >/dev/null <<SQL
INSERT INTO outbox_messages(message_id,message_type,message_version,message_kind,producer,routing_key,occurred_at,correlation_id,causation_id,actor_json,payload_json,status,attempt_count,available_at,published_at,created_at)
VALUES('$result_id','GetReassignmentCandidatesResult',1,'RESULT','wash-execution','results.bff',CURRENT_TIMESTAMP,'$correlation_id','$query_id','{}','{}','PUBLISHED',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d platform_bff_db >/dev/null <<SQL
INSERT INTO reassignment_candidates_refresh(wash_execution_id,execution_version,query_message_id,correlation_id,status,attempt_count,published_at,result_message_id,requested_at,updated_at)
VALUES('$EXECUTION_ID',2,'$query_id','$correlation_id','APPLIED',1,CURRENT_TIMESTAMP,'$result_id',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO reassignment_candidates_read_model(wash_execution_id,execution_version,result_message_id,query_message_id,correlation_id,snapshot_generated_at,fresh_until,updated_at)
VALUES('$EXECUTION_ID',2,'$result_id','$query_id','$correlation_id',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '10 seconds',CURRENT_TIMESTAMP);
INSERT INTO inbox_messages(consumer_name,message_id,message_kind,message_type,message_version,producer,correlation_id,causation_id,received_at,applied_at)
VALUES('bff-reassignment-candidates-results','$result_id','RESULT','GetReassignmentCandidatesResult',1,'wash-execution','$correlation_id','$query_id',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
if E2E_REPORT_DIR="$test_root/report" E2E_PSQL_EXEC="$test_root/fixture-psql" \
  E2E_TEST_FAIL_AFTER_BFF_CLEANUP=1 "$fixture" cleanup >/dev/null 2>&1; then
  printf 'injected post-BFF cleanup failure unexpectedly succeeded\n' >&2; exit 1
fi
[ "$(sed -n 's/^TRACKED_QUERY_IDS=//p' "$test_root/report/reassignment-candidates.env")" = "$query_id" ]
[ "$(docker exec "$container" psql -X -U postgres -Atq -d platform_bff_db -c "SELECT count(*) FROM reassignment_candidates_refresh WHERE wash_execution_id='$EXECUTION_ID'")" = 0 ]
if E2E_REPORT_DIR="$test_root/report" E2E_PSQL_EXEC="$test_root/fixture-psql" \
  E2E_TEST_FAIL_AFTER_OWNER_CLEANUP=1 "$fixture" cleanup >/dev/null 2>&1; then
  printf 'injected post-owner cleanup failure unexpectedly succeeded\n' >&2; exit 1
fi
[ -e "$test_root/report/reassignment-candidates.env" ]
E2E_REPORT_DIR="$test_root/report" E2E_PSQL_EXEC="$test_root/fixture-psql" "$fixture" cleanup >/dev/null
[ ! -e "$test_root/report/reassignment-candidates.env" ]

# A bootstrap that fails after the owner transaction must self-clean without Query evidence.
if E2E_REPORT_DIR="$test_root/report" E2E_PSQL_EXEC="$test_root/fixture-psql" \
  E2E_TEST_FAIL_AFTER_OWNER_BOOTSTRAP=1 "$fixture" bootstrap >/dev/null 2>&1; then
  printf 'injected partial bootstrap unexpectedly succeeded\n' >&2; exit 1
fi
[ ! -e "$test_root/report/reassignment-candidates.env" ]
[ "$(docker exec "$container" psql -X -U postgres -Atq -d wash_execution_db -c 'SELECT count(*) FROM wash_executions')" = 0 ]

# A complete bootstrap can also clean safely before the scheduler creates its first Query.
E2E_REPORT_DIR="$test_root/report" E2E_PSQL_EXEC="$test_root/fixture-psql" "$fixture" bootstrap >/dev/null
E2E_REPORT_DIR="$test_root/report" E2E_PSQL_EXEC="$test_root/fixture-psql" "$fixture" cleanup >/dev/null
[ ! -e "$test_root/report/reassignment-candidates.env" ]
printf 'Reassignment candidates fixture passed real PostgreSQL schema, retry, partial-bootstrap and no-Query cleanup.\n'

#!/bin/sh
set -eu

mode=${1:-}
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
platform_dir=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
report_dir=${E2E_REPORT_DIR:-$platform_dir/target/e2e/wash-supervisor}
manifest=${E2E_SUPERVISOR_FIXTURE_MANIFEST:-$report_dir/supervisor-fixture.env}
student_manifest=${E2E_STUDENT_FIXTURE_MANIFEST:-$report_dir/student-fixture.env}
bff_database=${E2E_BFF_DATABASE:-platform_bff_db}

fail() {
  printf 'wash-supervisor fixture: %s\n' "$*" >&2
  exit 1
}

require_runtime() {
  : "${E2E_PSQL_EXEC:?E2E_PSQL_EXEC must name an executable that accepts a database and reads SQL on stdin}"
  [ -x "$E2E_PSQL_EXEC" ] || fail "E2E_PSQL_EXEC is not executable"
}

sql() {
  database=$1
  "$E2E_PSQL_EXEC" "$database"
}

require_uuid() {
  value=$1
  name=$2
  case "$value" in
    *[!0-9A-Fa-f-]*) fail "$name must be a UUID" ;;
    ????????-????-????-????-????????????) ;;
    *) fail "$name must be a UUID" ;;
  esac
}

require_fixture_login() {
  value=$1
  case "$value" in e2e-*) ;; *) fail "fixture login identifier must begin with e2e-" ;; esac
  case "$value" in *[!A-Za-z0-9._@+-]*) fail "fixture login identifier contains unsafe characters" ;; esac
}

new_uuid() {
  sed -n '1p' /proc/sys/kernel/random/uuid
}

manifest_value_from() {
  source_manifest=$1
  key=$2
  value=$(sed -n "s/^${key}=//p" "$source_manifest")
  [ -n "$value" ] || fail "manifest is missing $key"
  [ "$(printf '%s\n' "$value" | wc -l)" -eq 1 ] || fail "manifest repeats $key"
  printf '%s\n' "$value"
}

manifest_value() {
  manifest_value_from "$manifest" "$1"
}

student_value() {
  manifest_value_from "$student_manifest" "$1"
}

bootstrap() {
  require_runtime
  : "${E2E_SUPERVISOR_ACCOUNT_ID:?E2E_SUPERVISOR_ACCOUNT_ID is required}"
  : "${E2E_SUPERVISOR_LOGIN_IDENTIFIER:?E2E_SUPERVISOR_LOGIN_IDENTIFIER is required}"
  require_uuid "$E2E_SUPERVISOR_ACCOUNT_ID" E2E_SUPERVISOR_ACCOUNT_ID
  require_fixture_login "$E2E_SUPERVISOR_LOGIN_IDENTIFIER"
  [ -f "$student_manifest" ] || fail "student fixture manifest does not exist"
  [ ! -e "$manifest" ] || fail "supervisor fixture manifest already exists; run cleanup first"

  student_account_id=$(student_value ACCOUNT_ID)
  slot_id=$(student_value SLOT_ID)
  run_key=$(student_value RUN_KEY)
  require_uuid "$student_account_id" student_ACCOUNT_ID
  require_uuid "$slot_id" student_SLOT_ID
  [ "$student_account_id" != "$E2E_SUPERVISOR_ACCOUNT_ID" ] || fail "student and supervisor must be different accounts"
  case "$run_key" in e2e-wash-*) ;; *) fail "student manifest has an unsafe run key" ;; esac
  case "$run_key" in *[!A-Za-z0-9-]*) fail "student manifest has an unsafe run key" ;; esac

  identity_matches=$(sql "$bff_database" <<SQL
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM account_presentation_read_model
  WHERE account_id = '$E2E_SUPERVISOR_ACCOUNT_ID'::uuid
    AND login_identifier = '$E2E_SUPERVISOR_LOGIN_IDENTIFIER'
    AND account_status = 'ACTIVE'
) THEN 'yes' ELSE 'no' END;
SQL
  )
  [ "$identity_matches" = yes ] || fail "supervisor account presentation is not active or does not match the login"

  original_role=$(sql "$bff_database" <<SQL
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM authorization_role_projection
  WHERE account_id = '$E2E_SUPERVISOR_ACCOUNT_ID'::uuid
) THEN (
  SELECT COALESCE(role_code, '<null>') || '|' ||
         COALESCE(role_source_version::text, '<null>') || '|' ||
         extract(epoch FROM updated_at)::text
  FROM authorization_role_projection
  WHERE account_id = '$E2E_SUPERVISOR_ACCOUNT_ID'::uuid
) ELSE '<absent>' END;
SQL
  )
  case "$original_role" in
    '<absent>')
      role_was_absent=1
      original_role_source_version='<null>'
      original_role_updated_epoch='<null>'
      ;;
    E2E_TESTER\|*)
      role_was_absent=0
      prior_ifs=$IFS
      IFS='|'
      set -- $original_role
      IFS=$prior_ifs
      [ "$#" -eq 3 ] || fail "could not capture the complete original supervisor role"
      [ "$1" = E2E_TESTER ] || fail "supervisor role is not an isolated E2E_TESTER fixture"
      original_role_source_version=$2
      original_role_updated_epoch=$3
      case "$original_role_source_version" in '<null>'|*[!0-9]*)
        [ "$original_role_source_version" = '<null>' ] || fail "unsafe original role source version"
        ;;
      esac
      case "$original_role_updated_epoch" in *[!0-9.]*) fail "unsafe original role timestamp" ;; esac
      ;;
    *) fail "supervisor role is not absent or E2E_TESTER" ;;
  esac

  existing_access=$(sql "$bff_database" <<SQL
SELECT count(*) FROM authorization_access_projection
WHERE account_id = '$E2E_SUPERVISOR_ACCOUNT_ID'::uuid
  AND system_code = 'LAVADO_ULTRASONICO';
SQL
  )
  [ "$existing_access" = 0 ] || fail "synthetic supervisor already has BFF Lavado access"

  readiness=$(sql "$bff_database" <<SQL
SELECT count(*) FROM projection_status
WHERE projection_name IN ('person','academic','authorization','system-catalog','appointments',
  'wash-execution','wash-administration') AND status = 'READY';
SQL
  )
  [ "$readiness" = 7 ] || fail "required BFF projections are not READY"

  system_id=$(sql "$bff_database" <<SQL
SELECT system_id FROM authorization_system_catalog_read_model
WHERE system_code = 'LAVADO_ULTRASONICO' AND status = 'ACTIVE' AND trusted = TRUE;
SQL
  )
  require_uuid "$system_id" 'trusted Lavado system catalog id'
  system_access_id=$(new_uuid)
  operation_count_before=$(sql "$bff_database" <<SQL
SELECT count(*) FROM client_operations
WHERE actor_account_id = '$E2E_SUPERVISOR_ACCOUNT_ID'::uuid;
SQL
  )
  case "$operation_count_before" in *[!0-9]*) fail "could not capture supervisor operation baseline" ;; esac

  mkdir -p "$report_dir"
  chmod 700 "$report_dir"
  umask 077
  {
    printf 'SUPERVISOR_ACCOUNT_ID=%s\n' "$E2E_SUPERVISOR_ACCOUNT_ID"
    printf 'SUPERVISOR_LOGIN_IDENTIFIER=%s\n' "$E2E_SUPERVISOR_LOGIN_IDENTIFIER"
    printf 'STUDENT_ACCOUNT_ID=%s\n' "$student_account_id"
    printf 'SLOT_ID=%s\n' "$slot_id"
    printf 'RUN_KEY=%s\n' "$run_key"
    printf 'SYSTEM_ID=%s\n' "$system_id"
    printf 'SYSTEM_ACCESS_ID=%s\n' "$system_access_id"
    printf 'ROLE_WAS_ABSENT=%s\n' "$role_was_absent"
    printf 'ORIGINAL_ROLE_SOURCE_VERSION=%s\n' "$original_role_source_version"
    printf 'ORIGINAL_ROLE_UPDATED_EPOCH=%s\n' "$original_role_updated_epoch"
    printf 'OPERATION_COUNT_BEFORE=%s\n' "$operation_count_before"
  } > "$manifest"

  cleanup_on_error() {
    trap - EXIT INT TERM
    "$0" cleanup >/dev/null 2>&1 || true
  }
  trap cleanup_on_error EXIT INT TERM

  sql "$bff_database" >/dev/null <<SQL
BEGIN;
INSERT INTO authorization_role_projection
  (account_id, role_code, role_source_version, updated_at)
VALUES ('$E2E_SUPERVISOR_ACCOUNT_ID'::uuid, 'SUPERVISOR_LAVADO', 1, CURRENT_TIMESTAMP)
ON CONFLICT (account_id) DO UPDATE
SET role_code = 'SUPERVISOR_LAVADO', updated_at = CURRENT_TIMESTAMP;

INSERT INTO authorization_access_projection
  (system_access_id, account_id, system_id, system_code, status, access_source_version, updated_at)
VALUES ('$system_access_id'::uuid, '$E2E_SUPERVISOR_ACCOUNT_ID'::uuid, '$system_id'::uuid,
  'LAVADO_ULTRASONICO', 'ACTIVE', 1, CURRENT_TIMESTAMP);
COMMIT;
SQL

  trap - EXIT INT TERM
  printf 'Synthetic wash-supervisor authorization is ready; runtime manifest: %s\n' "$manifest"
}

load_manifest() {
  [ -f "$manifest" ] || fail "supervisor fixture manifest does not exist"
  SUPERVISOR_ACCOUNT_ID=$(manifest_value SUPERVISOR_ACCOUNT_ID)
  SUPERVISOR_LOGIN_IDENTIFIER=$(manifest_value SUPERVISOR_LOGIN_IDENTIFIER)
  STUDENT_ACCOUNT_ID=$(manifest_value STUDENT_ACCOUNT_ID)
  SLOT_ID=$(manifest_value SLOT_ID)
  RUN_KEY=$(manifest_value RUN_KEY)
  SYSTEM_ID=$(manifest_value SYSTEM_ID)
  SYSTEM_ACCESS_ID=$(manifest_value SYSTEM_ACCESS_ID)
  ROLE_WAS_ABSENT=$(manifest_value ROLE_WAS_ABSENT)
  ORIGINAL_ROLE_SOURCE_VERSION=$(manifest_value ORIGINAL_ROLE_SOURCE_VERSION)
  ORIGINAL_ROLE_UPDATED_EPOCH=$(manifest_value ORIGINAL_ROLE_UPDATED_EPOCH)
  OPERATION_COUNT_BEFORE=$(manifest_value OPERATION_COUNT_BEFORE)
  require_uuid "$SUPERVISOR_ACCOUNT_ID" SUPERVISOR_ACCOUNT_ID
  require_uuid "$STUDENT_ACCOUNT_ID" STUDENT_ACCOUNT_ID
  require_uuid "$SLOT_ID" SLOT_ID
  require_uuid "$SYSTEM_ID" SYSTEM_ID
  require_uuid "$SYSTEM_ACCESS_ID" SYSTEM_ACCESS_ID
  require_fixture_login "$SUPERVISOR_LOGIN_IDENTIFIER"
  case "$RUN_KEY" in e2e-wash-*) ;; *) fail "unsafe manifest run key" ;; esac
  case "$RUN_KEY" in *[!A-Za-z0-9-]*) fail "unsafe manifest run key" ;; esac
  case "$ROLE_WAS_ABSENT" in 0|1) ;; *) fail "unsafe original role flag" ;; esac
  case "$OPERATION_COUNT_BEFORE" in *[!0-9]*) fail "unsafe operation baseline" ;; esac
  if [ "$ROLE_WAS_ABSENT" = 0 ]; then
    case "$ORIGINAL_ROLE_SOURCE_VERSION" in '<null>'|*[!0-9]*)
      [ "$ORIGINAL_ROLE_SOURCE_VERSION" = '<null>' ] || fail "unsafe original role source version"
      ;;
    esac
    case "$ORIGINAL_ROLE_UPDATED_EPOCH" in *[!0-9.]*) fail "unsafe original role timestamp" ;; esac
  fi
}

verify_identity() {
  identity_matches=$(sql "$bff_database" <<SQL
SELECT CASE WHEN EXISTS (
  SELECT 1 FROM account_presentation_read_model
  WHERE account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid
    AND login_identifier = '$SUPERVISOR_LOGIN_IDENTIFIER'
) THEN 'yes' ELSE 'no' END;
SQL
  )
  [ "$identity_matches" = yes ] || fail "manifest no longer matches the synthetic supervisor"
}

audit() {
  require_runtime
  load_manifest
  verify_identity
  operation_count_after=$(sql "$bff_database" <<SQL
SELECT count(*) FROM client_operations
WHERE actor_account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid;
SQL
  )
  [ "$operation_count_after" = "$OPERATION_COUNT_BEFORE" ] ||
    fail "supervisor Home/lookup unexpectedly created a client operation"
  execution_count=$(sql "$bff_database" <<SQL
SELECT count(*)
FROM wash_execution_read_model execution
JOIN appointment_read_model appointment USING (appointment_id)
WHERE appointment.student_account_id = '$STUDENT_ACCOUNT_ID'::uuid
  AND appointment.appointment_time_slot_id = '$SLOT_ID'::uuid;
SQL
  )
  [ "$execution_count" = 0 ] || fail "supervisor lookup unexpectedly created a wash execution"
  printf 'Supervisor Home/lookup side-effect audit passed.\n'
}

cleanup() {
  require_runtime
  load_manifest
  verify_identity

  current_state=$(sql "$bff_database" <<SQL
SELECT COALESCE((SELECT role_code FROM authorization_role_projection
  WHERE account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid), '<absent>') || '|' ||
  (SELECT count(*) FROM authorization_access_projection
   WHERE system_access_id = '$SYSTEM_ACCESS_ID'::uuid
     AND account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid
     AND system_id = '$SYSTEM_ID'::uuid
     AND system_code = 'LAVADO_ULTRASONICO'
     AND status = 'ACTIVE');
SQL
  )
  if [ "$current_state" = 'SUPERVISOR_LAVADO|1' ]; then
    :
  elif [ "$ROLE_WAS_ABSENT" = 1 ] && [ "$current_state" = '<absent>|0' ]; then
    rm -f -- "$manifest"
    printf 'Synthetic wash-supervisor authorization was already cleaned.\n'
    return
  elif [ "$ROLE_WAS_ABSENT" = 0 ] && [ "$current_state" = 'E2E_TESTER|0' ]; then
    restored=$(sql "$bff_database" <<SQL
SELECT COALESCE(role_source_version::text, '<null>') || '|' ||
       extract(epoch FROM updated_at)::text
FROM authorization_role_projection
WHERE account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid;
SQL
    )
    [ "$restored" = "$ORIGINAL_ROLE_SOURCE_VERSION|$ORIGINAL_ROLE_UPDATED_EPOCH" ] ||
      fail "restored supervisor role no longer matches its manifest"
    rm -f -- "$manifest"
    printf 'Synthetic wash-supervisor authorization was already cleaned.\n'
    return
  else
    fail "temporary supervisor authorization no longer matches its manifest"
  fi

  sql "$bff_database" >/dev/null <<SQL
BEGIN;
DELETE FROM authorization_access_projection
WHERE system_access_id = '$SYSTEM_ACCESS_ID'::uuid
  AND account_id = '$SUPERVISOR_ACCOUNT_ID'::uuid;
$(if [ "$ROLE_WAS_ABSENT" = 1 ]; then
    printf "DELETE FROM authorization_role_projection WHERE account_id = '%s'::uuid AND role_code = 'SUPERVISOR_LAVADO';" "$SUPERVISOR_ACCOUNT_ID"
  else
    if [ "$ORIGINAL_ROLE_SOURCE_VERSION" = '<null>' ]; then role_version_sql=NULL; else role_version_sql=$ORIGINAL_ROLE_SOURCE_VERSION; fi
    printf "UPDATE authorization_role_projection SET role_code = 'E2E_TESTER', role_source_version = %s, updated_at = to_timestamp(%s) WHERE account_id = '%s'::uuid AND role_code = 'SUPERVISOR_LAVADO';" "$role_version_sql" "$ORIGINAL_ROLE_UPDATED_EPOCH" "$SUPERVISOR_ACCOUNT_ID"
  fi)
COMMIT;
SQL

  if [ "${E2E_TEST_FAIL_AFTER_DB_CLEANUP:-0}" = 1 ]; then
    fail "injected failure after supervisor authorization cleanup"
  fi
  rm -f -- "$manifest"
  printf 'Synthetic wash-supervisor authorization cleaned.\n'
}

case "$mode" in
  bootstrap) bootstrap ;;
  audit) audit ;;
  cleanup) cleanup ;;
  *) fail "usage: $0 bootstrap|audit|cleanup" ;;
esac

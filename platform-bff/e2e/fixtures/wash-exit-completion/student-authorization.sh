#!/bin/sh
# Authorization projection fixture for a fresh isolated student in Wash Execution.
set -eu
mode=${1:?bootstrap or cleanup required}
: "${E2E_REPORT_DIR:?report directory required}"
: "${E2E_PSQL_EXEC:?isolated executor required}"
[ "${E2E_ISOLATED_NAMESPACE:-}" = wash-e2e ] || exit 2
"$E2E_PSQL_EXEC" --assert-isolated-wash >/dev/null
manifest="$E2E_REPORT_DIR/exit-student-authorization.env"
entry="${E2E_ENTRY_FIXTURE_MANIFEST:-$E2E_REPORT_DIR/entry-fixture.env}"
value() {
  [ "$(stat -c %a "$1")" = 600 ] || exit 2
  result=$(sed -n "s/^$2=//p" "$1")
  [ "$(printf '%s\n' "$result" | wc -l)" = 1 ] && [ -n "$result" ] || exit 2
  printf '%s' "$result"
}
uuid() {
  case "$1" in ????????-????-????-????-????????????) ;; *) exit 2 ;; esac
  case "$1" in *[!0-9a-fA-F-]*) exit 2 ;; esac
}
case "$mode" in
  bootstrap)
    [ ! -e "$manifest" ] || { echo 'Student owner authorization manifest exists' >&2; exit 1; }
    account=$(value "$entry" STUDENT_ACCOUNT_ID); system=$(value "$entry" SYSTEM_ID)
    uuid "$account"; uuid "$system"
    epoch=$(date +%s)
    umask 077
    printf 'ACCOUNT_ID=%s\nSYSTEM_ID=%s\nCREATED_EPOCH=%s\n' "$account" "$system" "$epoch" > "$manifest"
    ;;
  cleanup)
    [ -e "$manifest" ] || exit 0
    account=$(value "$manifest" ACCOUNT_ID); system=$(value "$manifest" SYSTEM_ID)
    epoch=$(value "$manifest" CREATED_EPOCH)
    uuid "$account"; uuid "$system"
    ;;
  *) exit 2 ;;
esac
case "$epoch" in ''|*[!0-9]*) exit 2 ;; esac
if [ "$mode" = bootstrap ]; then
  "$E2E_PSQL_EXEC" wash_execution_db >/dev/null <<SQL
BEGIN;
LOCK TABLE authorization_role_projection, authorization_access_projection IN SHARE ROW EXCLUSIVE MODE;
DO \$guard\$ BEGIN
 IF EXISTS (SELECT 1 FROM authorization_role_projection WHERE account_id='$account'::uuid)
 OR EXISTS (SELECT 1 FROM authorization_access_projection WHERE account_id='$account'::uuid) THEN
   RAISE EXCEPTION 'student owner authorization already exists; refusing overwrite';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM appointment_projection WHERE student_account_id='$account'::uuid) THEN
   RAISE EXCEPTION 'student is not the prepared appointment owner';
 END IF;
END \$guard\$;
INSERT INTO authorization_role_projection(account_id,role_code,role_source_version,updated_at)
 VALUES('$account','ALUMNO',1,to_timestamp($epoch));
INSERT INTO authorization_access_projection(account_id,system_id,system_code,access_status,access_source_version,updated_at)
 VALUES('$account','$system','LAVADO_ULTRASONICO','ACTIVE',1,to_timestamp($epoch));
COMMIT;
SQL
  printf 'Isolated student owner authorization prepared.\n'
else
  "$E2E_PSQL_EXEC" wash_execution_db >/dev/null <<SQL
BEGIN;
LOCK TABLE authorization_role_projection, authorization_access_projection, appointment_projection IN SHARE ROW EXCLUSIVE MODE;
DO \$guard\$ BEGIN
 IF EXISTS (SELECT 1 FROM appointment_projection WHERE student_account_id='$account'::uuid) THEN
   RAISE EXCEPTION 'entry dependencies must be cleaned before student authorization';
 END IF;
 IF EXISTS (SELECT 1 FROM authorization_role_projection WHERE account_id='$account'::uuid
   AND NOT (role_code='ALUMNO' AND role_source_version=1 AND updated_at=to_timestamp($epoch)))
 OR EXISTS (SELECT 1 FROM authorization_access_projection WHERE account_id='$account'::uuid
   AND NOT (system_id='$system'::uuid AND system_code='LAVADO_ULTRASONICO' AND access_status='ACTIVE'
     AND access_source_version=1 AND updated_at=to_timestamp($epoch))) THEN
   RAISE EXCEPTION 'student owner authorization changed; preserve it';
 END IF;
END \$guard\$;
DELETE FROM authorization_access_projection WHERE account_id='$account'::uuid;
DELETE FROM authorization_role_projection WHERE account_id='$account'::uuid;
COMMIT;
SQL
  rm -f -- "$manifest"
  printf 'Isolated student owner authorization cleaned.\n'
fi

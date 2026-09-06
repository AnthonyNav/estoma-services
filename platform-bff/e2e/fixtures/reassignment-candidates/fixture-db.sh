#!/bin/sh
set -eu

mode=${1:-}
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
platform_dir=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
report_dir=${E2E_REPORT_DIR:-$platform_dir/target/e2e/reassignment-candidates}
manifest=${E2E_CANDIDATE_FIXTURE_MANIFEST:-$report_dir/reassignment-candidates.env}
identity_manifest=${E2E_IDENTITY_MANIFEST:-$report_dir/identity-fixture.env}
bff_database=${E2E_BFF_DATABASE:-platform_bff_db}
execution_database=${E2E_WASH_EXECUTION_DATABASE:-wash_execution_db}

fail() { printf 'reassignment-candidates fixture: %s\n' "$*" >&2; exit 1; }
require_runtime() {
  : "${E2E_PSQL_EXEC:?E2E_PSQL_EXEC is required}"
  [ -x "$E2E_PSQL_EXEC" ] || fail "E2E_PSQL_EXEC is not executable"
  "$E2E_PSQL_EXEC" --assert-isolated-wash >/dev/null
}
sql() { "$E2E_PSQL_EXEC" "$1"; }
new_uuid() { sed -n '1p' /proc/sys/kernel/random/uuid; }
require_uuid() {
  case "$1" in *[!0-9A-Fa-f-]*|'') fail "$2 must be a UUID" ;; ????????-????-????-????-????????????) ;; *) fail "$2 must be a UUID" ;; esac
}
value_from() {
  value=$(sed -n "s/^${2}=//p" "$1")
  [ -n "$value" ] || fail "$1 is missing $2"
  [ "$(printf '%s\n' "$value" | wc -l)" -eq 1 ] || fail "$1 repeats $2"
  printf '%s\n' "$value"
}
manifest_value() { value_from "$manifest" "$1"; }
optional_manifest_value() { sed -n "s/^${1}=//p" "$manifest"; }
csv_to_query_array() {
  raw_csv=$1; QUERY_ARRAY='ARRAY[]::uuid[]'; [ -n "$raw_csv" ] || return 0
  quoted=; old_ifs=$IFS; IFS=,
  for query_id in $raw_csv; do
    require_uuid "$query_id" TRACKED_QUERY_ID
    if [ -n "$quoted" ]; then quoted="$quoted,'$query_id'"; else quoted="'$query_id'"; fi
  done
  IFS=$old_ifs; QUERY_ARRAY="ARRAY[$quoted]::uuid[]"
}
persist_query_ids() {
  raw_csv=$1; tmp_manifest="$manifest.tmp.$$"
  umask 077
  sed '/^TRACKED_QUERY_IDS=/d' "$manifest" > "$tmp_manifest"
  printf 'TRACKED_QUERY_IDS=%s\n' "$raw_csv" >> "$tmp_manifest"
  chmod 600 "$tmp_manifest"; mv "$tmp_manifest" "$manifest"
}

bootstrap() {
  require_runtime
  [ -f "$identity_manifest" ] || fail "isolated identity manifest is missing"
  [ ! -e "$manifest" ] || fail "fixture manifest already exists; run cleanup first"
  supervisor_id=$(value_from "$identity_manifest" SUPERVISOR_ACCOUNT_ID)
  student_id=$(value_from "$identity_manifest" STUDENT_ACCOUNT_ID)
  require_uuid "$supervisor_id" SUPERVISOR_ACCOUNT_ID
  require_uuid "$student_id" STUDENT_ACCOUNT_ID
  execution_id=$(new_uuid); appointment_id=$(new_uuid); slot_id=$(new_uuid)
  calendar_id=$(new_uuid); cabin_id=$(new_uuid); tank_id=$(new_uuid); access_id=$(new_uuid)
  for pair in "$execution_id:EXECUTION_ID" "$appointment_id:APPOINTMENT_ID" "$slot_id:SLOT_ID" \
    "$calendar_id:CALENDAR_ID" "$cabin_id:CABIN_ID" "$tank_id:TANK_ID" "$access_id:ACCESS_ID"; do
    require_uuid "${pair%%:*}" "${pair#*:}"
  done
  suffix=$(printf '%s' "$execution_id" | tr -d '-' | cut -c1-12)
  cabin_code="E2E-CAND-$suffix"; tank_code="E2E-TANK-$suffix"

  system_id=$(sql "$bff_database" <<'SQL'
SELECT system_id FROM authorization_system_catalog_read_model
WHERE system_code='LAVADO_ULTRASONICO' AND status='ACTIVE' AND trusted=TRUE;
SQL
  )
  require_uuid "$system_id" SYSTEM_ID
  bff_ready=$(sql "$bff_database" <<SQL
SELECT (SELECT count(*) FROM projection_status WHERE projection_name IN ('authorization','wash-execution') AND status='READY') || '|' ||
 (SELECT count(*) FROM authorization_role_projection WHERE account_id='$supervisor_id'::uuid) || '|' ||
 (SELECT count(*) FROM authorization_access_projection WHERE account_id='$supervisor_id'::uuid AND system_code='LAVADO_ULTRASONICO') || '|' ||
 (SELECT count(*) FROM wash_execution_read_model WHERE wash_execution_id='$execution_id'::uuid OR appointment_id='$appointment_id'::uuid) || '|' ||
 (SELECT count(*) FROM reassignment_candidates_refresh WHERE wash_execution_id='$execution_id'::uuid) || '|' ||
 (SELECT count(*) FROM reassignment_candidates_refresh_history WHERE wash_execution_id='$execution_id'::uuid) || '|' ||
 (SELECT count(*) FROM reassignment_candidates_read_model WHERE wash_execution_id='$execution_id'::uuid) || '|' ||
 (SELECT count(*) FROM reassignment_candidate_read_model WHERE wash_execution_id='$execution_id'::uuid);
SQL
  )
  [ "$bff_ready" = '2|0|0|0|0|0|0|0' ] || fail "BFF fixture preconditions are not isolated and ready"
  owner_ready=$(sql "$execution_database" <<SQL
SELECT (SELECT count(*) FROM projection_recovery_state WHERE projection_name IN ('appointments','wash-administration') AND readiness_status='READY') || '|' ||
 (SELECT count(*) FROM wash_executions WHERE wash_execution_id='$execution_id'::uuid OR appointment_id='$appointment_id'::uuid) || '|' ||
 (SELECT count(*) FROM appointment_projection WHERE appointment_id='$appointment_id'::uuid) || '|' ||
 (SELECT count(*) FROM appointment_time_slot_projection WHERE appointment_time_slot_id='$slot_id'::uuid) || '|' ||
 (SELECT count(*) FROM cabin_projection WHERE cabin_id='$cabin_id'::uuid OR code='$cabin_code') || '|' ||
 (SELECT count(*) FROM tank_projection WHERE tank_id='$tank_id'::uuid OR code='$tank_code');
SQL
  )
  [ "$owner_ready" = '2|0|0|0|0|0' ] || fail "Wash Execution fixture preconditions are not isolated and ready"

  mkdir -p "$report_dir"; chmod 700 "$report_dir"; umask 077
  {
    printf 'SUPERVISOR_ACCOUNT_ID=%s\nSTUDENT_ACCOUNT_ID=%s\n' "$supervisor_id" "$student_id"
    printf 'EXECUTION_ID=%s\nAPPOINTMENT_ID=%s\nSLOT_ID=%s\nCALENDAR_ID=%s\n' "$execution_id" "$appointment_id" "$slot_id" "$calendar_id"
    printf 'CABIN_ID=%s\nTANK_ID=%s\nCABIN_CODE=%s\nTANK_CODE=%s\n' "$cabin_id" "$tank_id" "$cabin_code" "$tank_code"
    printf 'SYSTEM_ID=%s\nACCESS_ID=%s\n' "$system_id" "$access_id"
  } > "$manifest"
  chmod 600 "$manifest"
  cleanup_on_error() { trap - EXIT INT TERM; "$0" cleanup >/dev/null 2>&1 || true; }
  trap cleanup_on_error EXIT INT TERM

  sql "$execution_database" >/dev/null <<SQL
BEGIN;
INSERT INTO appointment_time_slot_projection(appointment_time_slot_id,calendar_id,service_date,starts_at,ends_at,timezone,arrival_tolerance_until,status,source_version,updated_at)
VALUES('$slot_id','$calendar_id',CURRENT_DATE,CURRENT_TIMESTAMP-INTERVAL '10 minutes',CURRENT_TIMESTAMP+INTERVAL '1 hour','America/Mexico_City',CURRENT_TIMESTAMP+INTERVAL '15 minutes','ACTIVE',1,CURRENT_TIMESTAMP);
INSERT INTO appointment_projection(appointment_id,student_account_id,appointment_time_slot_id,course_section_id,appointment_type,instrument_count,piece_type,appointment_status,entry_authorized_at,source_version,updated_at)
VALUES('$appointment_id','$student_id','$slot_id','$calendar_id','NORMAL',1,'E2E','IN_PROGRESS',CURRENT_TIMESTAMP-INTERVAL '5 minutes',2,CURRENT_TIMESTAMP);
INSERT INTO cabin_projection(cabin_id,code,name,cabin_type,status,source_version,updated_at)
VALUES('$cabin_id','$cabin_code','E2E Candidate Cabin','NORMAL','ACTIVE',1,CURRENT_TIMESTAMP);
INSERT INTO tank_projection(tank_id,cabin_id,code,name,configured_capacity,status,source_version,updated_at)
VALUES('$tank_id','$cabin_id','$tank_code','E2E Candidate Tank',2,'ACTIVE',1,CURRENT_TIMESTAMP);
INSERT INTO wash_executions(wash_execution_id,appointment_id,appointment_type,status,arrived_at,started_at,version,created_at,updated_at)
VALUES('$execution_id','$appointment_id','NORMAL','PENDING_REASSIGNMENT',CURRENT_TIMESTAMP-INTERVAL '8 minutes',CURRENT_TIMESTAMP-INTERVAL '5 minutes',2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
COMMIT;
SQL
  [ "${E2E_TEST_FAIL_AFTER_OWNER_BOOTSTRAP:-0}" != 1 ] || fail "injected failure after owner bootstrap"
  sql "$bff_database" >/dev/null <<SQL
BEGIN;
INSERT INTO authorization_role_projection(account_id,role_code,role_source_version,updated_at)
VALUES('$supervisor_id','SUPERVISOR_LAVADO',1,CURRENT_TIMESTAMP);
INSERT INTO authorization_access_projection(system_access_id,account_id,system_id,system_code,status,access_source_version,updated_at)
VALUES('$access_id','$supervisor_id','$system_id','LAVADO_ULTRASONICO','ACTIVE',1,CURRENT_TIMESTAMP);
INSERT INTO wash_execution_read_model(wash_execution_id,appointment_id,appointment_type,status,arrived_at,authorized_at,started_at,execution_version,updated_at)
VALUES('$execution_id','$appointment_id','NORMAL','PENDING_REASSIGNMENT',CURRENT_TIMESTAMP-INTERVAL '8 minutes',CURRENT_TIMESTAMP-INTERVAL '5 minutes',CURRENT_TIMESTAMP-INTERVAL '5 minutes',2,CURRENT_TIMESTAMP);
COMMIT;
SQL
  trap - EXIT INT TERM
  printf 'Reassignment candidate fixture ready: %s\n' "$manifest"
}

load_manifest() {
  [ -f "$manifest" ] || fail "fixture manifest is missing"
  [ "$(stat -c %a "$manifest")" = 600 ] || fail "fixture manifest must be mode 0600"
  SUPERVISOR_ACCOUNT_ID=$(manifest_value SUPERVISOR_ACCOUNT_ID); STUDENT_ACCOUNT_ID=$(manifest_value STUDENT_ACCOUNT_ID)
  EXECUTION_ID=$(manifest_value EXECUTION_ID); APPOINTMENT_ID=$(manifest_value APPOINTMENT_ID)
  SLOT_ID=$(manifest_value SLOT_ID); CALENDAR_ID=$(manifest_value CALENDAR_ID)
  CABIN_ID=$(manifest_value CABIN_ID); TANK_ID=$(manifest_value TANK_ID)
  SYSTEM_ID=$(manifest_value SYSTEM_ID); ACCESS_ID=$(manifest_value ACCESS_ID)
  require_uuid "$SUPERVISOR_ACCOUNT_ID" SUPERVISOR_ACCOUNT_ID; require_uuid "$STUDENT_ACCOUNT_ID" STUDENT_ACCOUNT_ID
  require_uuid "$EXECUTION_ID" EXECUTION_ID; require_uuid "$APPOINTMENT_ID" APPOINTMENT_ID
  require_uuid "$SLOT_ID" SLOT_ID; require_uuid "$CALENDAR_ID" CALENDAR_ID
  require_uuid "$CABIN_ID" CABIN_ID; require_uuid "$TANK_ID" TANK_ID
  require_uuid "$SYSTEM_ID" SYSTEM_ID; require_uuid "$ACCESS_ID" ACCESS_ID
  CABIN_CODE=$(manifest_value CABIN_CODE); TANK_CODE=$(manifest_value TANK_CODE)
  case "$CABIN_CODE:$TANK_CODE" in *[!A-Za-z0-9:-]*) fail "unsafe resource codes in manifest" ;; esac
  case "$CABIN_CODE:$TANK_CODE" in E2E-CAND-*:E2E-TANK-*) ;; *) fail "unsafe resource codes in manifest" ;; esac
}

deactivate_tank() {
  require_runtime; load_manifest
  changed=$(sql "$execution_database" <<SQL
UPDATE tank_projection SET status='INACTIVE',source_version=2,updated_at=CURRENT_TIMESTAMP
WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid AND code='$TANK_CODE'
  AND name='E2E Candidate Tank' AND configured_capacity=2 AND status='ACTIVE' AND source_version=1
RETURNING 1;
SQL
  )
  [ "$changed" = 1 ] || fail "tank no longer matches the active fixture state"
  printf 'Fixture tank is inactive; waiting for an owner-derived empty refresh is now valid.\n'
}

cleanup() {
  require_runtime; load_manifest
  cleanup_attempts=${E2E_CANDIDATE_CLEANUP_ATTEMPTS:-40}
  case "$cleanup_attempts" in *[!0-9]*|'') fail "invalid cleanup attempt limit" ;; esac
  [ "$cleanup_attempts" -ge 1 ] && [ "$cleanup_attempts" -le 120 ] || fail "cleanup attempt limit must be between 1 and 120"
  # Fence the scheduler first. No new legitimate refresh should be enqueued after this row is gone.
  sql "$bff_database" >/dev/null <<SQL
BEGIN;
LOCK TABLE wash_execution_read_model IN ACCESS EXCLUSIVE MODE;
DO \$guard\$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM wash_execution_read_model WHERE wash_execution_id='$EXECUTION_ID'::uuid
   AND appointment_id='$APPOINTMENT_ID'::uuid AND status='PENDING_REASSIGNMENT' AND execution_version=2)
   AND EXISTS (SELECT 1 FROM wash_execution_read_model WHERE wash_execution_id='$EXECUTION_ID'::uuid)
 THEN RAISE EXCEPTION 'BFF execution projection no longer matches the manifest'; END IF;
END \$guard\$;
DELETE FROM wash_execution_read_model WHERE wash_execution_id='$EXECUTION_ID'::uuid
 AND appointment_id='$APPOINTMENT_ID'::uuid AND status='PENDING_REASSIGNMENT' AND execution_version=2;
COMMIT;
SQL

  tracked_csv=$(optional_manifest_value TRACKED_QUERY_IDS)
  [ "$(printf '%s\n' "$tracked_csv" | wc -l)" -le 1 ] || fail "manifest repeats TRACKED_QUERY_IDS"
  attempts=0
  while :; do
    discovered_csv=$(sql "$bff_database" <<SQL
SELECT COALESCE(string_agg(query_message_id::text,',' ORDER BY query_message_id),'') FROM (
 SELECT query_message_id FROM reassignment_candidates_refresh WHERE wash_execution_id='$EXECUTION_ID'::uuid
 UNION SELECT query_message_id FROM reassignment_candidates_refresh_history WHERE wash_execution_id='$EXECUTION_ID'::uuid
) q;
SQL
    )
    case "$discovered_csv:$tracked_csv" in *[!0-9a-fA-F,:-]*) fail "unsafe tracked Query identity result" ;; esac
    combined_csv=$(printf '%s\n%s\n' "$tracked_csv" "$discovered_csv" | tr ',' '\n' | sed '/^$/d' | sort -u | paste -sd, -)
    csv_to_query_array "$combined_csv"; query_array=$QUERY_ARRAY
    v15_count=$(sql "$bff_database" <<SQL
SELECT (SELECT count(*) FROM reassignment_candidates_refresh WHERE wash_execution_id='$EXECUTION_ID'::uuid)+
 (SELECT count(*) FROM reassignment_candidates_refresh_history WHERE wash_execution_id='$EXECUTION_ID'::uuid);
SQL
    )
    owner_remaining=$(sql "$execution_database" <<SQL
SELECT
 (SELECT count(*) FROM outbox_messages WHERE causation_id=ANY($query_array) AND message_kind='RESULT'
   AND message_type='GetReassignmentCandidatesResult' AND producer='wash-execution') || '|' ||
 ((SELECT count(*) FROM wash_executions WHERE wash_execution_id='$EXECUTION_ID'::uuid)+
  (SELECT count(*) FROM appointment_projection WHERE appointment_id='$APPOINTMENT_ID'::uuid)+
  (SELECT count(*) FROM appointment_time_slot_projection WHERE appointment_time_slot_id='$SLOT_ID'::uuid)+
  (SELECT count(*) FROM cabin_projection WHERE cabin_id='$CABIN_ID'::uuid)+
  (SELECT count(*) FROM tank_projection WHERE tank_id='$TANK_ID'::uuid));
SQL
    )
    complete=1; old_ifs=$IFS; IFS=,
    for query_id in $combined_csv; do
      [ "$owner_remaining" != '0|0' ] || continue
      owner_evidence=$(sql "$execution_database" <<SQL
SELECT message_id::text || '|' || correlation_id::text FROM outbox_messages
WHERE causation_id='$query_id'::uuid AND message_kind='RESULT'
 AND message_type='GetReassignmentCandidatesResult' AND producer='wash-execution' AND status='PUBLISHED';
SQL
      )
      case "$owner_evidence" in ????????-????-????-????-????????????\|????????-????-????-????-????????????) ;; *) complete=0; continue ;; esac
      result_id=${owner_evidence%%|*}; correlation_id=${owner_evidence#*|}
      require_uuid "$result_id" RESULT_MESSAGE_ID; require_uuid "$correlation_id" RESULT_CORRELATION_ID
      if [ "$v15_count" -gt 0 ]; then
        bff_evidence=$(sql "$bff_database" <<SQL
SELECT
 (SELECT count(*) FROM (
   SELECT query_message_id,correlation_id FROM reassignment_candidates_refresh WHERE wash_execution_id='$EXECUTION_ID'::uuid
   UNION ALL SELECT query_message_id,correlation_id FROM reassignment_candidates_refresh_history WHERE wash_execution_id='$EXECUTION_ID'::uuid
  ) r WHERE r.query_message_id='$query_id'::uuid AND r.correlation_id='$correlation_id'::uuid) || '|' ||
 (SELECT count(*) FROM inbox_messages WHERE consumer_name='bff-reassignment-candidates-results'
   AND message_id='$result_id'::uuid AND message_kind='RESULT' AND message_type='GetReassignmentCandidatesResult'
   AND producer='wash-execution' AND causation_id='$query_id'::uuid AND correlation_id='$correlation_id'::uuid);
SQL
        )
        [ "$bff_evidence" = '1|1' ] || complete=0
      fi
    done
    IFS=$old_ifs
    [ "$complete" = 1 ] && break
    attempts=$((attempts+1)); [ "$attempts" -lt "$cleanup_attempts" ] || fail "cleanup refused while tracked Query/Result work is in flight"
    sleep 1
  done
  persist_query_ids "$combined_csv"

  # The lock closes the scheduler race: a transaction that selected the removed execution must
  # finish its refresh insert before these guards run. Unknown/unconsumed evidence fails closed.
  sql "$bff_database" >/dev/null <<SQL
BEGIN;
LOCK TABLE reassignment_candidates_refresh, reassignment_candidates_refresh_history,
 reassignment_candidates_read_model, reassignment_candidate_read_model, inbox_messages IN SHARE ROW EXCLUSIVE MODE;
DO \$guard\$ BEGIN
 IF EXISTS (SELECT 1 FROM wash_execution_read_model WHERE wash_execution_id='$EXECUTION_ID'::uuid) THEN
  RAISE EXCEPTION 'scheduler fence was reversed'; END IF;
 IF EXISTS (SELECT 1 FROM reassignment_candidates_refresh WHERE wash_execution_id='$EXECUTION_ID'::uuid AND query_message_id<>ALL($query_array))
 OR EXISTS (SELECT 1 FROM reassignment_candidates_refresh_history WHERE wash_execution_id='$EXECUTION_ID'::uuid AND query_message_id<>ALL($query_array)) THEN
  RAISE EXCEPTION 'an untracked refresh appeared during cleanup'; END IF;
 IF (EXISTS (SELECT 1 FROM reassignment_candidates_refresh WHERE wash_execution_id='$EXECUTION_ID'::uuid)
   OR EXISTS (SELECT 1 FROM reassignment_candidates_refresh_history WHERE wash_execution_id='$EXECUTION_ID'::uuid))
 AND (SELECT count(*) FROM inbox_messages WHERE consumer_name='bff-reassignment-candidates-results'
   AND causation_id=ANY($query_array) AND message_kind='RESULT'
   AND message_type='GetReassignmentCandidatesResult' AND producer='wash-execution') <> cardinality($query_array) THEN
  RAISE EXCEPTION 'not every tracked Result reached the BFF inbox'; END IF;
 IF NOT (((SELECT count(*) FROM authorization_role_projection WHERE account_id='$SUPERVISOR_ACCOUNT_ID'::uuid)=0
    AND (SELECT count(*) FROM authorization_access_projection WHERE account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND system_code='LAVADO_ULTRASONICO')=0)
  OR (EXISTS (SELECT 1 FROM authorization_role_projection WHERE account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
      AND role_code='SUPERVISOR_LAVADO' AND role_source_version=1)
    AND EXISTS (SELECT 1 FROM authorization_access_projection WHERE system_access_id='$ACCESS_ID'::uuid
      AND account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND system_id='$SYSTEM_ID'::uuid
      AND system_code='LAVADO_ULTRASONICO' AND status='ACTIVE' AND access_source_version=1))) THEN
  RAISE EXCEPTION 'temporary BFF supervisor authorization no longer matches the manifest'; END IF;
END \$guard\$;
DELETE FROM reassignment_candidate_read_model WHERE wash_execution_id='$EXECUTION_ID'::uuid;
DELETE FROM reassignment_candidates_read_model WHERE wash_execution_id='$EXECUTION_ID'::uuid;
DELETE FROM reassignment_candidates_refresh WHERE wash_execution_id='$EXECUTION_ID'::uuid AND query_message_id=ANY($query_array);
DELETE FROM reassignment_candidates_refresh_history WHERE wash_execution_id='$EXECUTION_ID'::uuid AND query_message_id=ANY($query_array);
DELETE FROM inbox_messages WHERE consumer_name='bff-reassignment-candidates-results'
 AND causation_id=ANY($query_array) AND message_kind='RESULT'
 AND message_type='GetReassignmentCandidatesResult' AND producer='wash-execution';
DELETE FROM authorization_access_projection WHERE system_access_id='$ACCESS_ID'::uuid AND account_id='$SUPERVISOR_ACCOUNT_ID'::uuid
 AND system_id='$SYSTEM_ID'::uuid AND system_code='LAVADO_ULTRASONICO' AND status='ACTIVE' AND access_source_version=1;
DELETE FROM authorization_role_projection WHERE account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND role_code='SUPERVISOR_LAVADO' AND role_source_version=1;
COMMIT;
SQL
  [ "${E2E_TEST_FAIL_AFTER_BFF_CLEANUP:-0}" != 1 ] || fail "injected failure after BFF cleanup"

  sql "$execution_database" >/dev/null <<SQL
BEGIN;
DO \$guard\$
DECLARE fixture_rows integer; result_rows integer;
BEGIN
 SELECT
  (SELECT count(*) FROM wash_executions WHERE wash_execution_id='$EXECUTION_ID'::uuid OR appointment_id='$APPOINTMENT_ID'::uuid)+
  (SELECT count(*) FROM appointment_projection WHERE appointment_id='$APPOINTMENT_ID'::uuid)+
  (SELECT count(*) FROM appointment_time_slot_projection WHERE appointment_time_slot_id='$SLOT_ID'::uuid)+
  (SELECT count(*) FROM cabin_projection WHERE cabin_id='$CABIN_ID'::uuid OR code='$CABIN_CODE')+
  (SELECT count(*) FROM tank_projection WHERE tank_id='$TANK_ID'::uuid OR code='$TANK_CODE') INTO fixture_rows;
 SELECT count(*) INTO result_rows FROM outbox_messages WHERE causation_id=ANY($query_array)
  AND message_kind='RESULT' AND message_type='GetReassignmentCandidatesResult'
  AND producer='wash-execution' AND status='PUBLISHED';
 IF fixture_rows=0 AND result_rows=0 THEN NULL;
 ELSIF result_rows<>cardinality($query_array)
  OR NOT EXISTS (SELECT 1 FROM wash_executions WHERE wash_execution_id='$EXECUTION_ID'::uuid AND appointment_id='$APPOINTMENT_ID'::uuid
    AND appointment_type='NORMAL' AND status='PENDING_REASSIGNMENT' AND version=2)
  OR NOT EXISTS (SELECT 1 FROM tank_projection WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid
    AND code='$TANK_CODE' AND name='E2E Candidate Tank' AND configured_capacity=2
    AND ((status='ACTIVE' AND source_version=1) OR (status='INACTIVE' AND source_version=2)))
  OR NOT EXISTS (SELECT 1 FROM cabin_projection WHERE cabin_id='$CABIN_ID'::uuid AND code='$CABIN_CODE'
    AND name='E2E Candidate Cabin' AND cabin_type='NORMAL' AND status='ACTIVE' AND source_version=1)
  OR NOT EXISTS (SELECT 1 FROM appointment_projection WHERE appointment_id='$APPOINTMENT_ID'::uuid
    AND student_account_id='$STUDENT_ACCOUNT_ID'::uuid AND appointment_time_slot_id='$SLOT_ID'::uuid
    AND appointment_type='NORMAL' AND appointment_status='IN_PROGRESS' AND source_version=2)
  OR NOT EXISTS (SELECT 1 FROM appointment_time_slot_projection WHERE appointment_time_slot_id='$SLOT_ID'::uuid
    AND calendar_id='$CALENDAR_ID'::uuid AND status='ACTIVE' AND source_version=1)
 THEN RAISE EXCEPTION 'owner fixture or tracked Result state no longer matches the manifest'; END IF;
 IF EXISTS (SELECT 1 FROM resource_assignments WHERE wash_execution_id='$EXECUTION_ID'::uuid)
 OR EXISTS (SELECT 1 FROM resource_unavailability_affected_executions WHERE wash_execution_id='$EXECUTION_ID'::uuid)
 OR EXISTS (SELECT 1 FROM cleaning_periods WHERE wash_execution_id='$EXECUTION_ID'::uuid) THEN
  RAISE EXCEPTION 'untracked owner dependents reference the fixture execution'; END IF;
END \$guard\$;
DELETE FROM outbox_messages WHERE causation_id=ANY($query_array) AND message_kind='RESULT' AND message_type='GetReassignmentCandidatesResult' AND producer='wash-execution';
DELETE FROM wash_executions WHERE wash_execution_id='$EXECUTION_ID'::uuid AND appointment_id='$APPOINTMENT_ID'::uuid AND status='PENDING_REASSIGNMENT' AND version=2;
DELETE FROM appointment_projection WHERE appointment_id='$APPOINTMENT_ID'::uuid AND student_account_id='$STUDENT_ACCOUNT_ID'::uuid AND appointment_time_slot_id='$SLOT_ID'::uuid;
DELETE FROM appointment_time_slot_projection WHERE appointment_time_slot_id='$SLOT_ID'::uuid AND calendar_id='$CALENDAR_ID'::uuid;
DELETE FROM tank_projection WHERE tank_id='$TANK_ID'::uuid AND cabin_id='$CABIN_ID'::uuid AND code='$TANK_CODE'
 AND ((status='ACTIVE' AND source_version=1) OR (status='INACTIVE' AND source_version=2));
DELETE FROM cabin_projection WHERE cabin_id='$CABIN_ID'::uuid AND code='$CABIN_CODE' AND name='E2E Candidate Cabin' AND cabin_type='NORMAL' AND status='ACTIVE' AND source_version=1;
COMMIT;
SQL
  [ "${E2E_TEST_FAIL_AFTER_OWNER_CLEANUP:-0}" != 1 ] || fail "injected failure after owner cleanup"
  bff_residual=$(sql "$bff_database" <<SQL
SELECT
 (SELECT count(*) FROM reassignment_candidates_refresh WHERE wash_execution_id='$EXECUTION_ID'::uuid)+
 (SELECT count(*) FROM reassignment_candidates_refresh_history WHERE wash_execution_id='$EXECUTION_ID'::uuid)+
 (SELECT count(*) FROM reassignment_candidates_read_model WHERE wash_execution_id='$EXECUTION_ID'::uuid)+
 (SELECT count(*) FROM reassignment_candidate_read_model WHERE wash_execution_id='$EXECUTION_ID'::uuid)+
 (SELECT count(*) FROM wash_execution_read_model WHERE wash_execution_id='$EXECUTION_ID'::uuid)+
 (SELECT count(*) FROM inbox_messages WHERE causation_id=ANY($query_array))+
 (SELECT count(*) FROM authorization_role_projection WHERE account_id='$SUPERVISOR_ACCOUNT_ID'::uuid)+
 (SELECT count(*) FROM authorization_access_projection WHERE account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND system_code='LAVADO_ULTRASONICO');
SQL
  )
  owner_residual=$(sql "$execution_database" <<SQL
SELECT
 (SELECT count(*) FROM outbox_messages WHERE causation_id=ANY($query_array))+
 (SELECT count(*) FROM wash_executions WHERE wash_execution_id='$EXECUTION_ID'::uuid)+
 (SELECT count(*) FROM appointment_projection WHERE appointment_id='$APPOINTMENT_ID'::uuid)+
 (SELECT count(*) FROM appointment_time_slot_projection WHERE appointment_time_slot_id='$SLOT_ID'::uuid)+
 (SELECT count(*) FROM cabin_projection WHERE cabin_id='$CABIN_ID'::uuid)+
 (SELECT count(*) FROM tank_projection WHERE tank_id='$TANK_ID'::uuid);
SQL
  )
  [ "$bff_residual:$owner_residual" = '0:0' ] || fail "post-cleanup manifest-owned row audit failed"
  rm -f -- "$manifest"
  printf 'Reassignment candidate fixture cleaned.\n'
}

case "$mode" in bootstrap) bootstrap ;; deactivate-tank) deactivate_tank ;; cleanup) cleanup ;; *) fail "usage: $0 bootstrap|deactivate-tank|cleanup" ;; esac

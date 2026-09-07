#!/bin/sh
set -eu

case "$(basename "$0")" in
  fixture-psql)
    [ "${1:-}" = --assert-isolated-wash ] && exit 0
    exec docker exec -i "$TEST_PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U postgres -Atq -d "$1"
    ;;
esac

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
platform_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
terminal_fixture="$platform_dir/e2e/fixtures/wash-exit-completion/fixture-db.sh"
test_root=$(mktemp -d)
container="wash-terminal-schema-$$"

cleanup_test() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf -- "$test_root"
}
trap cleanup_test EXIT INT TERM

cp "$0" "$test_root/fixture-psql"
chmod +x "$test_root/fixture-psql"
export TEST_PG_CONTAINER="$container"

docker run -d --rm --name "$container" --network none --cpus 2 --memory 512m \
  -e POSTGRES_PASSWORD=fixture postgres:16-alpine >/dev/null
attempt=0
until docker exec "$container" pg_isready -U postgres >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 30 ] || { printf 'local PostgreSQL did not become ready\n' >&2; exit 1; }
  sleep 1
done

for database in platform_bff_db wash_execution_db appointments_db; do
  docker exec "$container" createdb -U postgres "$database"
done
find "$platform_dir/src/main/resources/db/migration" -type f -name 'V*.sql' | sort -V | while IFS= read -r migration; do
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d platform_bff_db < "$migration" >/dev/null
done
find "$platform_dir/../wash-execution-service/src/main/resources/db/migration" -type f -name 'V*.sql' | sort -V | while IFS= read -r migration; do
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d wash_execution_db < "$migration" >/dev/null
done
find "$platform_dir/../appointments-service/src/main/resources/db/migration" -type f -name 'V*.sql' | sort -V | while IFS= read -r migration; do
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d appointments_db < "$migration" >/dev/null
done

report_dir="$test_root/report"
mkdir -p "$report_dir"
chmod 700 "$report_dir"
entry_manifest="$report_dir/entry-fixture.env"
terminal_manifest="$report_dir/exit-terminal-fixture.env"
student_manifest="$report_dir/student-fixture.env"
supervisor_manifest="$report_dir/supervisor-fixture.env"

student_id=00000000-0000-4000-8000-000000000001
supervisor_id=00000000-0000-4000-8000-000000000002
appointment_id=00000000-0000-4000-8000-000000000003
slot_id=00000000-0000-4000-8000-000000000004
calendar_id=00000000-0000-4000-8000-000000000005
system_id=00000000-0000-4000-8000-000000000006
cabin_id=00000000-0000-4000-8000-000000000007
tank_id=00000000-0000-4000-8000-000000000008
execution_id=00000000-0000-4000-8000-000000000020
assignment_id=00000000-0000-4000-8000-000000000021
arrival_message_id=00000000-0000-4000-8000-000000000030
authorized_message_id=00000000-0000-4000-8000-000000000031
assignment_message_id=00000000-0000-4000-8000-000000000032
started_message_id=00000000-0000-4000-8000-000000000033
exit_message_id=00000000-0000-4000-8000-000000000034
complete_message_id=00000000-0000-4000-8000-000000000035
exit_operation_id=00000000-0000-4000-8000-000000000040
stale_operation_id=00000000-0000-4000-8000-000000000041
complete_operation_id=00000000-0000-4000-8000-000000000042
duplicate_operation_id=00000000-0000-4000-8000-000000000043
run_key=e2e-wash-terminal-postgres

write_manifests() {
  : > "$student_manifest"
  : > "$supervisor_manifest"
  {
    printf 'SUPERVISOR_ACCOUNT_ID=%s\nSTUDENT_ACCOUNT_ID=%s\n' "$supervisor_id" "$student_id"
    printf 'APPOINTMENT_ID=%s\nSLOT_ID=%s\nCALENDAR_ID=%s\nSYSTEM_ID=%s\n' "$appointment_id" "$slot_id" "$calendar_id" "$system_id"
    printf 'RUN_KEY=%s\nREGISTER_KEY=%s-register-arrival\nDECIDE_KEY=%s-decide-entry\n' "$run_key" "$run_key" "$run_key"
    printf 'ROLE_WAS_ABSENT=1\nORIGINAL_ROLE_SOURCE_VERSION=0\nORIGINAL_ROLE_UPDATED_EPOCH=0\nFIXTURE_ROLE_UPDATED_EPOCH=1788600000.123456\n'
    printf 'CABIN_ID=%s\nTANK_ID=%s\n' "$cabin_id" "$tank_id"
    printf 'CABIN_CODE=E2E-terminal-postgres-CABIN\nTANK_CODE=E2E-terminal-postgres-TANK\n'
    printf 'CABIN_NAME=E2E-terminal-postgres-CABIN\nTANK_NAME=E2E-terminal-postgres-TANK\n'
  } > "$entry_manifest"
  {
    printf 'WASH_EXECUTION_ID=%s\nRESOURCE_ASSIGNMENT_ID=%s\n' "$execution_id" "$assignment_id"
    printf 'APPOINTMENT_ID=%s\nSLOT_ID=%s\nCABIN_ID=%s\nTANK_ID=%s\n' "$appointment_id" "$slot_id" "$cabin_id" "$tank_id"
    printf 'STUDENT_ACCOUNT_ID=%s\nSUPERVISOR_ACCOUNT_ID=%s\n' "$student_id" "$supervisor_id"
    printf 'INVALID_EXIT_KEY=%s-invalid-exit\nFOREIGN_EXIT_KEY=%s-foreign-exit\n' "$run_key" "$run_key"
    printf 'EXIT_KEY=%s-exit\nCOMPLETE_STALE_KEY=%s-complete-stale\nCOMPLETE_KEY=%s-complete\n' "$run_key" "$run_key" "$run_key"
    printf 'ORIGINAL_PACKAGE_COUNT=2\nORIGINAL_CASSETTE8_COUNT=1\nORIGINAL_CASSETTE10_COUNT=0\nORIGINAL_WITNESS_COUNT=2\n'
    printf 'FINAL_PACKAGE_COUNT=2\nFINAL_CASSETTE8_COUNT=1\nFINAL_CASSETTE10_COUNT=1\nFINAL_WITNESS_COUNT=2\n'
  } > "$terminal_manifest"
  chmod 600 "$entry_manifest" "$terminal_manifest" "$student_manifest" "$supervisor_manifest"
}

owner_event() {
  message_id=$1 message_type=$2 aggregate_version=$3 idempotency_key=$4 payload=$5
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d wash_execution_db >/dev/null <<SQL
INSERT INTO outbox_messages
 (message_id,message_type,message_version,message_kind,producer,routing_key,occurred_at,idempotency_key,
  aggregate_id,aggregate_version,correlation_id,actor_json,payload_json,status,attempt_count,available_at,published_at,created_at)
VALUES ('$message_id','$message_type',1,'EVENT','wash-execution','events.wash',CURRENT_TIMESTAMP,
 '$idempotency_key','$execution_id',$aggregate_version,'00000000-0000-4000-8000-000000000090','{}','$payload',
 'PUBLISHED',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
}

bff_inbox() {
  message_id=$1 message_type=$2 aggregate_version=$3
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d platform_bff_db >/dev/null <<SQL
INSERT INTO inbox_messages
 (consumer_name,message_id,message_kind,message_type,message_version,producer,correlation_id,aggregate_id,aggregate_version,received_at,applied_at)
VALUES ('bff-projection-runtime','$message_id','EVENT','$message_type',1,'wash-execution',
 '00000000-0000-4000-8000-000000000090','$execution_id',$aggregate_version,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
}

appointment_message() {
  message_id=$1 message_type=$2
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d appointments_db >/dev/null <<SQL
INSERT INTO processed_messages(message_id,message_type,status,received_at,applied_at)
VALUES ('$message_id','$message_type','APPLIED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
}

seed_case() {
  final_state=$1
  write_manifests
  if [ "$final_state" = COMPLETED ]; then
    owner_status=COMPLETED owner_version=4 owner_package=2 owner_cassette8=1 owner_cassette10=1 owner_witness=2
    owner_completed_by="'$supervisor_id'" owner_completed_at="TIMESTAMPTZ '2030-01-01 12:00:00+00'"
    appointment_status=COMPLETED appointment_version=4 appointment_completed_by="'$supervisor_id'" appointment_completed_at="TIMESTAMPTZ '2030-01-01 12:00:00+00'"
    bff_completed_at="TIMESTAMPTZ '2030-01-01 12:00:00+00'" bff_final="2,1,1,2,4" assignment_released="TIMESTAMPTZ '2030-01-01 12:00:00+00'" assignment_source_version=4
  else
    owner_status=EXIT_SUBMITTED owner_version=3 owner_package=2 owner_cassette8=1 owner_cassette10=0 owner_witness=2
    owner_completed_by=NULL owner_completed_at=NULL
    appointment_status=IN_PROGRESS appointment_version=3 appointment_completed_by=NULL appointment_completed_at=NULL
    bff_completed_at=NULL bff_final="NULL,NULL,NULL,NULL,0" assignment_released=NULL assignment_source_version=3
  fi

  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d wash_execution_db >/dev/null <<SQL
INSERT INTO wash_executions
 (wash_execution_id,appointment_id,appointment_type,status,arrived_at,started_at,
  exit_submitted_by_account_id,exit_submitted_at,package_count,green_paper_cassette_8_count,
  green_paper_cassette_10_count,witness_tape_portion_count,completed_by_account_id,completed_at,
  version,created_at,updated_at)
VALUES ('$execution_id','$appointment_id','NORMAL','$owner_status',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
 '$student_id',CURRENT_TIMESTAMP,$owner_package,$owner_cassette8,$owner_cassette10,$owner_witness,
 $owner_completed_by,$owner_completed_at,$owner_version,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO entry_validations
 (entry_validation_id,wash_execution_id,identity_confirmed,requirements_satisfied,decision,rejection_reason,
  decided_by_account_id,decided_at,created_at)
VALUES ('00000000-0000-4000-8000-000000000022','$execution_id',true,true,'AUTHORIZED',NULL,
 '$supervisor_id',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO resource_assignments
 (resource_assignment_id,wash_execution_id,appointment_time_slot_id,cabin_id,tank_id,assignment_type,
  assigned_by_account_id,assigned_at,released_by_account_id,released_at,release_reason,created_at)
VALUES ('$assignment_id','$execution_id','$slot_id','$cabin_id','$tank_id','INITIAL',NULL,CURRENT_TIMESTAMP,
 $( [ "$final_state" = COMPLETED ] && printf "'%s'" "$supervisor_id" || printf 'NULL' ),$assignment_released,
 $( [ "$final_state" = COMPLETED ] && printf "'NORMAL_COMPLETION'" || printf 'NULL' ),CURRENT_TIMESTAMP);
SQL
  owner_event "$arrival_message_id" WashArrivalRegistered 1 "$run_key-register-arrival" '{}'
  owner_event "$authorized_message_id" WashEntryAuthorized 2 "$run_key-decide-entry" '{}'
  owner_event "$assignment_message_id" WashResourceAssigned 2 "$run_key-decide-entry" '{}'
  owner_event "$started_message_id" WashExecutionStarted 2 "$run_key-decide-entry" '{}'
  owner_event "$exit_message_id" WashExitSubmitted 3 "$run_key-exit" '{"packageCount":2,"greenPaperCassette8Count":1,"greenPaperCassette10Count":0,"witnessTapePortionCount":2}'
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d wash_execution_db >/dev/null <<SQL
INSERT INTO command_idempotency_records
 (producer,operation_type,idempotency_key,request_fingerprint,status,result_json,aggregate_id,aggregate_version,created_at,completed_at)
VALUES ('bff','SubmitWashExit','$run_key-exit','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
 'SUCCEEDED','{}','$execution_id',3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
  if [ "$final_state" = COMPLETED ]; then
    owner_event "$complete_message_id" WashExecutionCompleted 4 "$run_key-complete" '{"finalPackageCount":2,"finalGreenPaperCassette8Count":1,"finalGreenPaperCassette10Count":1,"finalWitnessTapePortionCount":2}'
    docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d wash_execution_db >/dev/null <<SQL
INSERT INTO command_idempotency_records
 (producer,operation_type,idempotency_key,request_fingerprint,status,result_json,error_code,aggregate_id,aggregate_version,created_at,completed_at)
VALUES
 ('bff','CompleteWashExecution','$run_key-complete-stale','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','REJECTED','{}','VERSION_CONFLICT',NULL,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
 ('bff','CompleteWashExecution','$run_key-complete','cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','SUCCEEDED','{}',NULL,'$execution_id',4,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
  fi

  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d platform_bff_db >/dev/null <<SQL
INSERT INTO appointment_read_model
 (appointment_id,appointment_time_slot_id,student_account_id,appointment_type,instrument_count,piece_type,course_section_id,status,source_version,updated_at)
VALUES ('$appointment_id','$slot_id','$student_id','NORMAL',1,'HIGH_SPEED','00000000-0000-4000-8000-000000000009',
 '$appointment_status',$appointment_version,CURRENT_TIMESTAMP);
INSERT INTO wash_execution_read_model
 (wash_execution_id,appointment_id,appointment_type,status,arrived_at,authorized_at,started_at,exit_submitted_at,
  completed_at,execution_version,updated_at,submitted_package_count,submitted_green_paper_cassette_8_count,
  submitted_green_paper_cassette_10_count,submitted_witness_tape_portion_count,submitted_exit_version,
  final_package_count,final_green_paper_cassette_8_count,final_green_paper_cassette_10_count,
  final_witness_tape_portion_count,final_exit_version)
VALUES ('$execution_id','$appointment_id','NORMAL','$owner_status',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
 CURRENT_TIMESTAMP,$bff_completed_at,$owner_version,CURRENT_TIMESTAMP,2,1,0,2,3,$bff_final);
INSERT INTO wash_resource_assignment_read_model
 (resource_assignment_id,wash_execution_id,appointment_id,appointment_time_slot_id,cabin_id,tank_id,
  assignment_type,assigned_at,released_at,source_message_id,source_version,updated_at)
VALUES ('$assignment_id','$execution_id','$appointment_id','$slot_id','$cabin_id','$tank_id','INITIAL',
 CURRENT_TIMESTAMP,$assignment_released,'$assignment_message_id',$assignment_source_version,CURRENT_TIMESTAMP);
INSERT INTO client_operations
 (operation_id,actor_account_id,public_operation,command_type,owner_logical_component_id,command_routing_key,
  correlation_id,idempotency_key,status,result_data,created_at,updated_at,completed_at)
VALUES ('$exit_operation_id','$student_id','wash-submit-exit','SubmitWashExit','wash-execution','commands.wash',
 '00000000-0000-4000-8000-000000000090','$run_key-exit','SUCCEEDED','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
  if [ "$final_state" = COMPLETED ]; then
    docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d platform_bff_db >/dev/null <<SQL
INSERT INTO client_operations
 (operation_id,actor_account_id,public_operation,command_type,owner_logical_component_id,command_routing_key,
  correlation_id,idempotency_key,status,result_data,error_code,created_at,updated_at,completed_at)
VALUES
 ('$stale_operation_id','$supervisor_id','wash-complete-execution','CompleteWashExecution','wash-execution','commands.wash','00000000-0000-4000-8000-000000000091','$run_key-complete-stale','REJECTED','{}','VERSION_CONFLICT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
 ('$complete_operation_id','$supervisor_id','wash-complete-execution','CompleteWashExecution','wash-execution','commands.wash','00000000-0000-4000-8000-000000000092','$run_key-complete','SUCCEEDED','{}',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
  fi
  bff_inbox "$arrival_message_id" WashArrivalRegistered 1
  bff_inbox "$authorized_message_id" WashEntryAuthorized 2
  bff_inbox "$assignment_message_id" WashResourceAssigned 2
  bff_inbox "$started_message_id" WashExecutionStarted 2
  bff_inbox "$exit_message_id" WashExitSubmitted 3
  [ "$final_state" != COMPLETED ] || bff_inbox "$complete_message_id" WashExecutionCompleted 4

  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d appointments_db >/dev/null <<SQL
INSERT INTO appointment_time_slots
 (appointment_time_slot_id,source_schedule_id,service_date,starts_at,ends_at,timezone,booking_deadline_at,
  cancellation_deadline_at,arrival_tolerance_until,status,version,calendar_id)
VALUES ('$slot_id','00000000-0000-4000-8000-000000000010',CURRENT_DATE,CURRENT_TIMESTAMP,
 CURRENT_TIMESTAMP+INTERVAL '1 hour','America/Mexico_City',CURRENT_TIMESTAMP-INTERVAL '1 hour',
 CURRENT_TIMESTAMP-INTERVAL '2 hours',CURRENT_TIMESTAMP+INTERVAL '15 minutes','ACTIVE',1,'$calendar_id');
INSERT INTO appointments
 (appointment_id,appointment_time_slot_id,student_account_id,course_section_id,appointment_type,instrument_count,
  piece_type,status,entry_authorized_at,started_at,completed_by_account_id,completed_at,created_at,updated_at,version)
VALUES ('$appointment_id','$slot_id','$student_id','00000000-0000-4000-8000-000000000009','NORMAL',1,
 'HIGH_SPEED','$appointment_status',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,$appointment_completed_by,
 $appointment_completed_at,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,$appointment_version);
INSERT INTO wash_execution_evidence_projection
 (evidence_id,wash_execution_id,appointment_id,arrived_at,arrival_execution_version,arrival_source_event_id,
  entry_authorized_at,entry_authorized_execution_version,entry_authorized_source_event_id,
  last_source_event_id,last_source_event_type,updated_at)
VALUES ('00000000-0000-4000-8000-000000000050','$execution_id','$appointment_id',CURRENT_TIMESTAMP,1,
 '$arrival_message_id',CURRENT_TIMESTAMP,2,'$authorized_message_id',
 $( [ "$final_state" = COMPLETED ] && printf "'%s'" "$complete_message_id" || printf "'%s'" "$exit_message_id" ),
 '$( [ "$final_state" = COMPLETED ] && printf 'WashExecutionCompleted' || printf 'WashExitSubmitted' )',CURRENT_TIMESTAMP);
INSERT INTO wash_execution_transition_projection
 (transition_id,wash_execution_id,appointment_id,event_type,execution_version,source_event_id,state,occurred_at,applied_at)
VALUES ('00000000-0000-4000-8000-000000000051','$execution_id','$appointment_id','WashExecutionStarted',2,
 '$started_message_id','IN_PROGRESS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
  for message in \
    "$arrival_message_id:WashArrivalRegistered" "$authorized_message_id:WashEntryAuthorized" \
    "$assignment_message_id:WashResourceAssigned" "$started_message_id:WashExecutionStarted" \
    "$exit_message_id:WashExitSubmitted"; do
    appointment_message "${message%%:*}" "${message#*:}"
  done
  if [ "$final_state" = COMPLETED ]; then
    appointment_message "$complete_message_id" WashExecutionCompleted
    docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d appointments_db >/dev/null <<SQL
INSERT INTO wash_execution_transition_projection
 (transition_id,wash_execution_id,appointment_id,event_type,execution_version,source_event_id,state,occurred_at,applied_at)
VALUES ('00000000-0000-4000-8000-000000000052','$execution_id','$appointment_id','WashExecutionCompleted',4,
 '$complete_message_id','COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
  fi
}

run_terminal_cleanup() {
  E2E_ISOLATED_NAMESPACE=wash-e2e E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$test_root/fixture-psql" \
    E2E_ENTRY_FIXTURE_MANIFEST="$entry_manifest" E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" \
    E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" E2E_EXIT_FIXTURE_MANIFEST="$terminal_manifest" \
    "$terminal_fixture" cleanup
}

prepare_partial_audit_evidence() {
  cp "$terminal_manifest" "$report_dir/exit-terminal-audit.env"
  printf 'EXIT_MESSAGE_ID=%s\nCOMPLETE_MESSAGE_ID=%s\n' "$exit_message_id" "$complete_message_id" \
    >> "$report_dir/exit-terminal-audit.env"
  chmod 600 "$report_dir/exit-terminal-audit.env"
}

# This is the Appointments-owned part normally performed later by the student fixture.
# It is deliberately explicit here: this test certifies the terminal fixture plus its
# absence audit, not the complete parent fixture chain.
teardown_dependent_appointment_seed() {
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d appointments_db >/dev/null <<SQL
DELETE FROM pending_wash_execution_events WHERE wash_execution_id='$execution_id';
DELETE FROM wash_execution_transition_projection WHERE wash_execution_id='$execution_id';
DELETE FROM wash_execution_evidence_projection WHERE wash_execution_id='$execution_id';
DELETE FROM processed_messages WHERE message_id IN ('$arrival_message_id','$authorized_message_id','$assignment_message_id','$started_message_id','$exit_message_id','$complete_message_id');
DELETE FROM appointments WHERE appointment_id='$appointment_id';
DELETE FROM appointment_time_slots WHERE appointment_time_slot_id='$slot_id';
SQL
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d platform_bff_db >/dev/null <<SQL
DELETE FROM appointment_read_model WHERE appointment_id='$appointment_id';
SQL
}

run_audit_clean() {
  E2E_ISOLATED_NAMESPACE=wash-e2e E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$test_root/fixture-psql" \
    E2E_ENTRY_FIXTURE_MANIFEST="$entry_manifest" E2E_EXIT_FIXTURE_MANIFEST="$terminal_manifest" \
    "$terminal_fixture" audit-clean >/dev/null
}

reset_rejected_case() {
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d wash_execution_db >/dev/null <<SQL
TRUNCATE wash_executions, outbox_messages, command_idempotency_records, processed_messages CASCADE;
SQL
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d platform_bff_db >/dev/null <<SQL
TRUNCATE client_operations CASCADE;
TRUNCATE inbox_messages, wash_execution_read_model, wash_resource_assignment_read_model, appointment_read_model CASCADE;
SQL
  docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d appointments_db >/dev/null <<SQL
TRUNCATE appointment_time_slots CASCADE;
TRUNCATE processed_messages CASCADE;
SQL
  rm -f -- "$entry_manifest" "$terminal_manifest" "$report_dir/exit-terminal-audit.env"
}

assert_rejected_seed_intact() {
  expected_operation_count=$1
  owner_counts=$(docker exec "$container" psql -X -U postgres -Atq -d wash_execution_db -c "
    SELECT (SELECT count(*) FROM wash_executions WHERE wash_execution_id='$execution_id') || '|' ||
      (SELECT count(*) FROM resource_assignments WHERE resource_assignment_id='$assignment_id') || '|' ||
      (SELECT count(*) FROM outbox_messages WHERE aggregate_id='$execution_id');")
  bff_counts=$(docker exec "$container" psql -X -U postgres -Atq -d platform_bff_db -c "
    SELECT (SELECT count(*) FROM wash_execution_read_model WHERE wash_execution_id='$execution_id') || '|' ||
      (SELECT count(*) FROM wash_resource_assignment_read_model WHERE resource_assignment_id='$assignment_id') || '|' ||
      (SELECT count(*) FROM client_operations WHERE idempotency_key IN ('$run_key-exit','$run_key-complete-stale','$run_key-complete'));")
  appointment_counts=$(docker exec "$container" psql -X -U postgres -Atq -d appointments_db -c "
    SELECT (SELECT count(*) FROM appointments WHERE appointment_id='$appointment_id') || '|' ||
      (SELECT count(*) FROM wash_execution_evidence_projection WHERE wash_execution_id='$execution_id') || '|' ||
      (SELECT count(*) FROM wash_execution_transition_projection WHERE wash_execution_id='$execution_id');")
  [ "$owner_counts" = '1|1|6' ]
  [ "$bff_counts" = "1|1|$expected_operation_count" ]
  [ "$appointment_counts" = '1|1|2' ]
  [ -f "$terminal_manifest" ] && [ -f "$entry_manifest" ]
}

seed_case EXIT_SUBMITTED
prepare_partial_audit_evidence
run_terminal_cleanup >/dev/null
[ "$(docker exec "$container" psql -X -U postgres -Atq -d wash_execution_db -c "SELECT count(*) FROM wash_executions WHERE wash_execution_id='$execution_id'")" = 0 ]
[ "$(docker exec "$container" psql -X -U postgres -Atq -d platform_bff_db -c "SELECT count(*) FROM wash_execution_read_model WHERE wash_execution_id='$execution_id'")" = 0 ]
teardown_dependent_appointment_seed
run_audit_clean
printf 'EXIT_SUBMITTED terminal cleanup and scoped absence audit passed.\n'

seed_case COMPLETED
E2E_ISOLATED_NAMESPACE=wash-e2e E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$test_root/fixture-psql" \
  E2E_ENTRY_FIXTURE_MANIFEST="$entry_manifest" E2E_EXIT_FIXTURE_MANIFEST="$terminal_manifest" \
  "$terminal_fixture" audit >/dev/null
if E2E_TEST_FAIL_AFTER_OWNER_CLEANUP=1 run_terminal_cleanup >/dev/null 2>&1; then
  printf 'injected post-owner cleanup failure unexpectedly succeeded\n' >&2
  exit 1
fi
[ "$(docker exec "$container" psql -X -U postgres -Atq -d wash_execution_db -c "SELECT count(*) FROM wash_executions WHERE wash_execution_id='$execution_id'")" = 0 ]
[ "$(docker exec "$container" psql -X -U postgres -Atq -d platform_bff_db -c "SELECT count(*) FROM wash_execution_read_model WHERE wash_execution_id='$execution_id'")" = 1 ]
[ -f "$terminal_manifest" ]
run_terminal_cleanup >/dev/null
teardown_dependent_appointment_seed
run_audit_clean
printf 'COMPLETED audit, owner/BFF split failure, retry, and scoped absence audit passed.\n'

seed_case COMPLETED
docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d wash_execution_db >/dev/null <<SQL
UPDATE wash_executions SET status='CANCELLED', exit_submitted_by_account_id=NULL, exit_submitted_at=NULL,
 package_count=NULL, green_paper_cassette_8_count=NULL, green_paper_cassette_10_count=NULL,
 witness_tape_portion_count=NULL, completed_by_account_id=NULL, completed_at=NULL,
 cancellation_type='CLINIC', cancellation_subreason='TEST_FOREIGN_STATE', cancellation_reason='synthetic',
 cancelled_by_account_id='$supervisor_id', cancelled_at=CURRENT_TIMESTAMP WHERE wash_execution_id='$execution_id';
SQL
if run_terminal_cleanup >/dev/null 2>&1; then
  printf 'foreign terminal state unexpectedly accepted\n' >&2
  exit 1
fi
[ "$(docker exec "$container" psql -X -U postgres -Atq -d wash_execution_db -c "SELECT count(*) FROM wash_executions WHERE wash_execution_id='$execution_id' AND status='CANCELLED'")" = 1 ]
assert_rejected_seed_intact 3
printf 'Foreign terminal state rejection preserved owner, BFF, Appointments, and manifest rows.\n'
reset_rejected_case

seed_case COMPLETED
docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U postgres -d platform_bff_db >/dev/null <<SQL
INSERT INTO client_operations
 (operation_id,actor_account_id,public_operation,command_type,owner_logical_component_id,command_routing_key,
  correlation_id,idempotency_key,status,result_data,created_at,updated_at,completed_at)
VALUES ('$duplicate_operation_id','$supervisor_id','wash-complete-execution','CompleteWashExecution','wash-execution',
 'commands.wash','00000000-0000-4000-8000-000000000093','$run_key-complete','SUCCEEDED','{}',
 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
SQL
if run_terminal_cleanup >/dev/null 2>&1; then
  printf 'ambiguous terminal operations unexpectedly accepted\n' >&2
  exit 1
fi
assert_rejected_seed_intact 4
[ "$(docker exec "$container" psql -X -U postgres -Atq -d platform_bff_db -c "SELECT count(*) FROM client_operations WHERE idempotency_key='$run_key-complete'")" = 2 ]
printf 'Ambiguous terminal operation rejection preserved owner, BFF, Appointments, and manifest rows.\n'

printf 'Wash exit/completion fixture passed real PostgreSQL 16 migrations and terminal cleanup safety cases.\n'

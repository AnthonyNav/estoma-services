#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
e2e_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
platform_dir=$(CDPATH= cd -- "$e2e_dir/.." && pwd)
report_dir=${E2E_REPORT_DIR:-$platform_dir/target/e2e/wash-supervisor-entry}
student_fixture="$e2e_dir/fixtures/wash-student/fixture-db.sh"
supervisor_fixture="$e2e_dir/fixtures/wash-supervisor/fixture-db.sh"
entry_fixture="$e2e_dir/fixtures/wash-supervisor-entry/fixture-db.sh"
student_manifest="$report_dir/student-fixture.env"
supervisor_manifest="$report_dir/supervisor-fixture.env"
entry_manifest="$report_dir/entry-fixture.env"
image=${E2E_RUNNER_IMAGE:-platform-bff-e2e:local}
entry_outcome=${E2E_ENTRY_OUTCOME:-rejected}
suite=wash-supervisor-entry
case "$entry_outcome" in
  rejected) ;;
  authorized)
    : "${E2E_PSQL_EXEC:?isolated executor required}"
    "$E2E_PSQL_EXEC" --assert-isolated-wash
    [ "${E2E_ISOLATED_NAMESPACE:-}" = wash-e2e ] || exit 2
    entry_fixture="$e2e_dir/fixtures/wash-supervisor-authorized/fixture-db.sh"
    suite=wash-supervisor-authorized
    ;;
  *) printf 'E2E_ENTRY_OUTCOME must be rejected or authorized\n' >&2; exit 2 ;;
esac

: "${BFF_BASE_URL:?BFF_BASE_URL is required}"
: "${E2E_STUDENT_LOGIN_IDENTIFIER:?E2E_STUDENT_LOGIN_IDENTIFIER is required}"
: "${E2E_STUDENT_PASSWORD:?E2E_STUDENT_PASSWORD is required}"
: "${E2E_SUPERVISOR_LOGIN_IDENTIFIER:?E2E_SUPERVISOR_LOGIN_IDENTIFIER is required}"
: "${E2E_SUPERVISOR_PASSWORD:?E2E_SUPERVISOR_PASSWORD is required}"
: "${E2E_PSQL_EXEC:?E2E_PSQL_EXEC is required}"

for login in "$E2E_STUDENT_LOGIN_IDENTIFIER" "$E2E_SUPERVISOR_LOGIN_IDENTIFIER"; do
  case "$login" in e2e-*) ;; *) printf 'wash-supervisor-entry requires two e2e-* login identifiers\n' >&2; exit 2 ;; esac
  case "$login" in *[!A-Za-z0-9._@+-]*) printf 'wash-supervisor-entry login contains unsafe characters\n' >&2; exit 2 ;; esac
done
[ "$E2E_STUDENT_LOGIN_IDENTIFIER" != "$E2E_SUPERVISOR_LOGIN_IDENTIFIER" ] || {
  printf 'wash-supervisor-entry requires different student and supervisor identities\n' >&2
  exit 2
}

mkdir -p "$report_dir"
chmod 700 "$report_dir"
temp_dir=$(mktemp -d)
runtime_environment="$temp_dir/postman-environment.json"
prepare_state="$temp_dir/postman-environment-prepare.json"
decision_state="$temp_dir/postman-environment-decision.json"
student_ready=0
supervisor_ready=0
entry_ready=0
prepare_started=0
student_bootstrap_token=
supervisor_bootstrap_token=
for state in "$prepare_state" "$decision_state"; do : > "$state"; chmod 600 "$state"; done

logout_token() {
  token=$1
  [ -n "$token" ] || return 0
  curl -sS --max-time 5 -o /dev/null -X POST -H "Authorization: Bearer $token" "$BFF_BASE_URL/api/v1/auth/logout" >/dev/null 2>&1 || true
}

logout_environment() {
  environment=$1
  [ -s "$environment" ] || return 0
  for key in runtimeAccessToken runtimeSupervisorAccessToken; do
    token=$(jq -er --arg key "$key" '.values[] | select(.key == $key) | .value | select(length > 0)' "$environment" 2>/dev/null || true)
    logout_token "$token"
    unset token
  done
}

finish() {
  status=$?
  trap - EXIT INT TERM
  audit_status=0
  cleanup_status=0
  logout_token "$student_bootstrap_token"
  logout_token "$supervisor_bootstrap_token"
  logout_environment "$runtime_environment"
  logout_environment "$prepare_state"
  logout_environment "$decision_state"

  if [ "$entry_ready" -eq 1 ] && [ "$status" -eq 0 ]; then
    E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$E2E_PSQL_EXEC" E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" E2E_ENTRY_FIXTURE_MANIFEST="$entry_manifest" E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" E2E_APPOINTMENTS_DATABASE="${E2E_APPOINTMENTS_DATABASE:-appointments_db}" E2E_WASH_EXECUTION_DATABASE="${E2E_WASH_EXECUTION_DATABASE:-wash_execution_db}" "$entry_fixture" audit || audit_status=$?
  fi
  if [ -f "$entry_manifest" ]; then
    E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$E2E_PSQL_EXEC" E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" E2E_ENTRY_FIXTURE_MANIFEST="$entry_manifest" E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" E2E_APPOINTMENTS_DATABASE="${E2E_APPOINTMENTS_DATABASE:-appointments_db}" E2E_WASH_EXECUTION_DATABASE="${E2E_WASH_EXECUTION_DATABASE:-wash_execution_db}" "$entry_fixture" cleanup || cleanup_status=$?
  elif [ "$prepare_started" -eq 1 ]; then
    printf 'Prepare started without an entry manifest; preserve dependencies to inspect any scheduled appointment.\n' >&2
    cleanup_status=1
  fi
  if [ "$student_ready" -eq 1 ] && [ "$cleanup_status" -eq 0 ]; then
    E2E_REPORT_DIR="$report_dir" E2E_FIXTURE_MANIFEST="$student_manifest" E2E_PSQL_EXEC="$E2E_PSQL_EXEC" E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" E2E_APPOINTMENTS_DATABASE="${E2E_APPOINTMENTS_DATABASE:-appointments_db}" "$student_fixture" cleanup || cleanup_status=$?
  fi
  if [ "$supervisor_ready" -eq 1 ] && [ "$cleanup_status" -eq 0 ]; then
    E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$E2E_PSQL_EXEC" E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" "$supervisor_fixture" cleanup || cleanup_status=$?
  fi
  rm -f -- "$runtime_environment" "$prepare_state" "$decision_state" \
    "$temp_dir/platform-bff-wash-entry-student-bootstrap-body.json" \
    "$temp_dir/platform-bff-wash-entry-student-bootstrap-response.json" \
    "$temp_dir/platform-bff-wash-entry-supervisor-bootstrap-body.json" \
    "$temp_dir/platform-bff-wash-entry-supervisor-bootstrap-response.json"
  rmdir -- "$temp_dir" 2>/dev/null || true
  if [ "$audit_status" -ne 0 ]; then
    printf 'Entry audit failed; cleanup was still attempted.\n' >&2
    exit "$audit_status"
  fi
  if [ "$cleanup_status" -ne 0 ]; then
    printf 'Fixture cleanup failed; preserve %s and retry cleanup.\n' "$report_dir" >&2
    exit "$cleanup_status"
  fi
  exit "$status"
}
trap finish EXIT INT TERM

discover_account() {
  login=$1
  password=$2
  device=$3
  response="$temp_dir/$device-response.json"
  body="$temp_dir/$device-body.json"
  jq -n --arg login "$login" --arg password "$password" --arg device "$device" '{loginType:"USERNAME", loginIdentifier:$login, password:$password, deviceName:$device}' > "$body"
  code=$(curl -sS --connect-timeout 5 --max-time 30 -o "$response" -w '%{http_code}' -H 'Content-Type: application/json' --data-binary "@$body" "$BFF_BASE_URL/api/v1/auth/login")
  [ "$code" = 200 ] || { printf 'synthetic fixture login failed with HTTP %s\n' "$code" >&2; return 1; }
  account_id=$(jq -er '.accountId | select(test("^[0-9a-fA-F-]{36}$"))' "$response")
  access_token=$(jq -er '.accessToken | select(length > 0)' "$response")
  printf '%s|%s\n' "$account_id" "$access_token"
}

run_phase() {
  phase=$1
  input_environment=$2
  output_environment=$3
  docker run --rm --network host --user "$(id -u):$(id -g)" -e BFF_BASE_URL="$BFF_BASE_URL" -e E2E_SUITE="$suite" -e E2E_PHASE="$phase" -e E2E_ALLOW_MUTATIONS="isolated-${suite}-fixture" -e E2E_POSTMAN_ENVIRONMENT=/run/secrets/postman-environment.json -e E2E_POSTMAN_STATE=/run/state/postman-environment-after.json -v "$input_environment:/run/secrets/postman-environment.json:ro" -v "$output_environment:/run/state/postman-environment-after.json" -v "$report_dir:/reports" "$image"
}

umask 077
docker build -q -f "$script_dir/Dockerfile" -t "$image" "$e2e_dir" >/dev/null
student_discovery=$(discover_account "$E2E_STUDENT_LOGIN_IDENTIFIER" "$E2E_STUDENT_PASSWORD" platform-bff-wash-entry-student-bootstrap)
student_account_id=${student_discovery%%|*}
student_bootstrap_token=${student_discovery#*|}
supervisor_discovery=$(discover_account "$E2E_SUPERVISOR_LOGIN_IDENTIFIER" "$E2E_SUPERVISOR_PASSWORD" platform-bff-wash-entry-supervisor-bootstrap)
supervisor_account_id=${supervisor_discovery%%|*}
supervisor_bootstrap_token=${supervisor_discovery#*|}
[ "$student_account_id" != "$supervisor_account_id" ] || {
  printf 'discovery resolved both identities to the same account\n' >&2
  exit 1
}
logout_token "$student_bootstrap_token"; student_bootstrap_token=
logout_token "$supervisor_bootstrap_token"; supervisor_bootstrap_token=

E2E_ACCOUNT_ID="$student_account_id" E2E_LOGIN_IDENTIFIER="$E2E_STUDENT_LOGIN_IDENTIFIER" E2E_WASH_SLOT_PROFILE=arrival E2E_REPORT_DIR="$report_dir" E2E_FIXTURE_MANIFEST="$student_manifest" E2E_PSQL_EXEC="$E2E_PSQL_EXEC" E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" E2E_APPOINTMENTS_DATABASE="${E2E_APPOINTMENTS_DATABASE:-appointments_db}" "$student_fixture" bootstrap
student_ready=1

E2E_SUPERVISOR_ACCOUNT_ID="$supervisor_account_id" E2E_SUPERVISOR_LOGIN_IDENTIFIER="$E2E_SUPERVISOR_LOGIN_IDENTIFIER" E2E_REPORT_DIR="$report_dir" E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" E2E_PSQL_EXEC="$E2E_PSQL_EXEC" E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" "$supervisor_fixture" bootstrap
supervisor_ready=1

RUN_KEY=$(sed -n 's/^RUN_KEY=//p' "$student_manifest")
COURSE_SECTION_ID=$(sed -n 's/^COURSE_SECTION_ID=//p' "$student_manifest")
SLOT_ID=$(sed -n 's/^SLOT_ID=//p' "$student_manifest")
STUDENT_ENROLLMENT=$(sed -n 's/^NRC=//p' "$student_manifest")

jq --arg baseUrl "$BFF_BASE_URL" --arg login "$E2E_STUDENT_LOGIN_IDENTIFIER" --arg password "$E2E_STUDENT_PASSWORD" --arg supervisorLogin "$E2E_SUPERVISOR_LOGIN_IDENTIFIER" --arg supervisorPassword "$E2E_SUPERVISOR_PASSWORD" --arg studentAccountId "$student_account_id" --arg supervisorAccountId "$supervisor_account_id" --arg runKey "$RUN_KEY" --arg courseSectionId "$COURSE_SECTION_ID" --arg appointmentTimeSlotId "$SLOT_ID" --arg studentEnrollment "$STUDENT_ENROLLMENT" '(.values[] | select(.key == "baseUrl").value) = $baseUrl
   | (.values[] | select(.key == "loginIdentifier").value) = $login
   | (.values[] | select(.key == "password").value) = $password
   | (.values[] | select(.key == "supervisorLoginIdentifier").value) = $supervisorLogin
   | (.values[] | select(.key == "supervisorPassword").value) = $supervisorPassword
   | (.values[] | select(.key == "fixtureStudentAccountId").value) = $studentAccountId
   | (.values[] | select(.key == "fixtureSupervisorAccountId").value) = $supervisorAccountId
   | (.values[] | select(.key == "runKey").value) = $runKey
   | (.values[] | select(.key == "fixtureCourseSectionId").value) = $courseSectionId
   | (.values[] | select(.key == "fixtureAppointmentTimeSlotId").value) = $appointmentTimeSlotId
   | (.values[] | select(.key == "fixtureStudentEnrollment").value) = $studentEnrollment' "$e2e_dir/postman/workflows/wash-supervisor-entry-front-v1.environment.example.json" > "$runtime_environment"

prepare_started=1
run_phase prepare "$runtime_environment" "$prepare_state"

E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$E2E_PSQL_EXEC" E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" E2E_ENTRY_FIXTURE_MANIFEST="$entry_manifest" E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" E2E_APPOINTMENTS_DATABASE="${E2E_APPOINTMENTS_DATABASE:-appointments_db}" E2E_WASH_EXECUTION_DATABASE="${E2E_WASH_EXECUTION_DATABASE:-wash_execution_db}" "$entry_fixture" bootstrap
entry_ready=1

if [ "$entry_outcome" = authorized ]; then
  cabin_id=$(sed -n 's/^CABIN_ID=//p' "$entry_manifest")
  tank_id=$(sed -n 's/^TANK_ID=//p' "$entry_manifest")
  jq --arg cabin "$cabin_id" --arg tank "$tank_id" '.values += [{key:"fixtureCabinId",value:$cabin,enabled:true},{key:"fixtureTankId",value:$tank,enabled:true}]' "$prepare_state" > "$runtime_environment"
  run_phase decision "$runtime_environment" "$decision_state"
else
run_phase decision "$prepare_state" "$decision_state"
fi

#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
e2e_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
platform_dir=$(CDPATH= cd -- "$e2e_dir/.." && pwd)
report_dir=${E2E_REPORT_DIR:-$platform_dir/target/e2e/wash-supervisor}
student_fixture="$e2e_dir/fixtures/wash-student/fixture-db.sh"
supervisor_fixture="$e2e_dir/fixtures/wash-supervisor/fixture-db.sh"
student_manifest="$report_dir/student-fixture.env"
supervisor_manifest="$report_dir/supervisor-fixture.env"
image=${E2E_RUNNER_IMAGE:-platform-bff-e2e:local}

: "${BFF_BASE_URL:?BFF_BASE_URL is required}"
: "${E2E_STUDENT_LOGIN_IDENTIFIER:?E2E_STUDENT_LOGIN_IDENTIFIER is required}"
: "${E2E_STUDENT_PASSWORD:?E2E_STUDENT_PASSWORD is required}"
: "${E2E_SUPERVISOR_LOGIN_IDENTIFIER:?E2E_SUPERVISOR_LOGIN_IDENTIFIER is required}"
: "${E2E_SUPERVISOR_PASSWORD:?E2E_SUPERVISOR_PASSWORD is required}"
: "${E2E_PSQL_EXEC:?E2E_PSQL_EXEC is required}"

for login in "$E2E_STUDENT_LOGIN_IDENTIFIER" "$E2E_SUPERVISOR_LOGIN_IDENTIFIER"; do
  case "$login" in e2e-*) ;; *) printf 'wash-supervisor runner requires two e2e-* login identifiers\n' >&2; exit 2 ;; esac
  case "$login" in *[!A-Za-z0-9._@+-]*) printf 'wash-supervisor login identifier contains unsafe characters\n' >&2; exit 2 ;; esac
done
[ "$E2E_STUDENT_LOGIN_IDENTIFIER" != "$E2E_SUPERVISOR_LOGIN_IDENTIFIER" ] || {
  printf 'wash-supervisor runner requires different student and supervisor identities\n' >&2
  exit 2
}

mkdir -p "$report_dir"
chmod 700 "$report_dir"
temp_dir=$(mktemp -d)
runtime_environment="$temp_dir/postman-environment.json"
runtime_state="$temp_dir/postman-environment-after.json"
student_fixture_ready=0
supervisor_fixture_ready=0
student_bootstrap_token=
supervisor_bootstrap_token=
: > "$runtime_state"
chmod 600 "$runtime_state"

logout_token() {
  token=$1
  [ -n "$token" ] || return 0
  curl -sS --max-time 5 -o /dev/null -X POST \
    -H "Authorization: Bearer $token" \
    "$BFF_BASE_URL/api/v1/auth/logout" >/dev/null 2>&1 || true
}

finish() {
  status=$?
  trap - EXIT INT TERM
  cleanup_status=0
  audit_status=0
  logout_token "$student_bootstrap_token"
  logout_token "$supervisor_bootstrap_token"
  if [ -f "$runtime_state" ]; then
    chmod 600 "$runtime_state"
    for key in runtimeAccessToken runtimeSupervisorAccessToken; do
      runtime_token=$(jq -er --arg key "$key" '.values[] | select(.key == $key) | .value | select(length > 0)' \
        "$runtime_state" 2>/dev/null || true)
      logout_token "$runtime_token"
      unset runtime_token
    done
  fi
  if [ "$supervisor_fixture_ready" -eq 1 ]; then
    E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$E2E_PSQL_EXEC" \
      E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" \
      E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" \
      E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" \
      "$supervisor_fixture" audit || audit_status=$?
  fi
  if [ "$student_fixture_ready" -eq 1 ]; then
    E2E_REPORT_DIR="$report_dir" E2E_FIXTURE_MANIFEST="$student_manifest" \
      E2E_PSQL_EXEC="$E2E_PSQL_EXEC" \
      E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" \
      E2E_APPOINTMENTS_DATABASE="${E2E_APPOINTMENTS_DATABASE:-appointments_db}" \
      "$student_fixture" cleanup || cleanup_status=$?
  fi
  if [ "$supervisor_fixture_ready" -eq 1 ]; then
    E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$E2E_PSQL_EXEC" \
      E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" \
      E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" \
      E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" \
      "$supervisor_fixture" cleanup || cleanup_status=$?
  fi
  rm -f -- "$runtime_environment" "$runtime_state"
  rmdir -- "$temp_dir" 2>/dev/null || true
  if [ "$audit_status" -ne 0 ]; then
    printf 'Supervisor side-effect audit failed; inspect the sanitized runtime status before another run.\n' >&2
    exit "$audit_status"
  fi
  if [ "$cleanup_status" -ne 0 ]; then
    printf 'Fixture cleanup failed; preserve and inspect %s before another run.\n' "$report_dir" >&2
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
  jq -n --arg login "$login" --arg password "$password" --arg device "$device" \
    '{loginType:"USERNAME", loginIdentifier:$login, password:$password, deviceName:$device}' > "$body"
  login_status=$(curl -sS -o "$response" -w '%{http_code}' \
    -H 'Content-Type: application/json' --data-binary "@$body" \
    "$BFF_BASE_URL/api/v1/auth/login")
  [ "$login_status" = 200 ] || {
    printf 'synthetic fixture login failed with HTTP %s\n' "$login_status" >&2
    return 1
  }
  account_id=$(jq -er '.accountId | select(test("^[0-9a-fA-F-]{36}$"))' "$response")
  access_token=$(jq -er '.accessToken | select(length > 0)' "$response")
  printf '%s|%s\n' "$account_id" "$access_token"
}

umask 077
student_discovery=$(discover_account "$E2E_STUDENT_LOGIN_IDENTIFIER" "$E2E_STUDENT_PASSWORD" \
  platform-bff-wash-supervisor-student-bootstrap)
student_account_id=${student_discovery%%|*}
student_bootstrap_token=${student_discovery#*|}
supervisor_discovery=$(discover_account "$E2E_SUPERVISOR_LOGIN_IDENTIFIER" "$E2E_SUPERVISOR_PASSWORD" \
  platform-bff-wash-supervisor-bootstrap)
supervisor_account_id=${supervisor_discovery%%|*}
supervisor_bootstrap_token=${supervisor_discovery#*|}
[ "$student_account_id" != "$supervisor_account_id" ] || {
  printf 'discovery resolved both identities to the same account\n' >&2
  exit 1
}
logout_token "$student_bootstrap_token"
student_bootstrap_token=
logout_token "$supervisor_bootstrap_token"
supervisor_bootstrap_token=

E2E_ACCOUNT_ID="$student_account_id" E2E_LOGIN_IDENTIFIER="$E2E_STUDENT_LOGIN_IDENTIFIER" \
  E2E_REPORT_DIR="$report_dir" E2E_FIXTURE_MANIFEST="$student_manifest" \
  E2E_PSQL_EXEC="$E2E_PSQL_EXEC" \
  E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" \
  E2E_APPOINTMENTS_DATABASE="${E2E_APPOINTMENTS_DATABASE:-appointments_db}" \
  "$student_fixture" bootstrap
student_fixture_ready=1

E2E_SUPERVISOR_ACCOUNT_ID="$supervisor_account_id" \
  E2E_SUPERVISOR_LOGIN_IDENTIFIER="$E2E_SUPERVISOR_LOGIN_IDENTIFIER" \
  E2E_REPORT_DIR="$report_dir" E2E_STUDENT_FIXTURE_MANIFEST="$student_manifest" \
  E2E_SUPERVISOR_FIXTURE_MANIFEST="$supervisor_manifest" \
  E2E_PSQL_EXEC="$E2E_PSQL_EXEC" \
  E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" \
  "$supervisor_fixture" bootstrap
supervisor_fixture_ready=1

RUN_KEY=$(sed -n 's/^RUN_KEY=//p' "$student_manifest")
COURSE_SECTION_ID=$(sed -n 's/^COURSE_SECTION_ID=//p' "$student_manifest")
SLOT_ID=$(sed -n 's/^SLOT_ID=//p' "$student_manifest")
STUDENT_ENROLLMENT=$(sed -n 's/^NRC=//p' "$student_manifest")
case "$RUN_KEY" in e2e-wash-[A-Za-z0-9-]*) ;; *) printf 'invalid runtime fixture manifest\n' >&2; exit 1 ;; esac
case "$RUN_KEY" in *[!A-Za-z0-9-]*) printf 'invalid runtime fixture manifest\n' >&2; exit 1 ;; esac
case "$COURSE_SECTION_ID:$SLOT_ID" in
  ????????-????-????-????-????????????:????????-????-????-????-????????????) ;;
  *) printf 'invalid runtime fixture identifiers\n' >&2; exit 1 ;;
esac
case "$COURSE_SECTION_ID$SLOT_ID" in *[!0-9A-Fa-f-]*) printf 'invalid runtime fixture identifiers\n' >&2; exit 1 ;; esac
case "$STUDENT_ENROLLMENT" in E2E-*) ;; *) printf 'invalid runtime fixture enrollment\n' >&2; exit 1 ;; esac
case "$STUDENT_ENROLLMENT" in *[!A-Za-z0-9-]*) printf 'invalid runtime fixture enrollment\n' >&2; exit 1 ;; esac

jq \
  --arg baseUrl "$BFF_BASE_URL" \
  --arg login "$E2E_STUDENT_LOGIN_IDENTIFIER" \
  --arg password "$E2E_STUDENT_PASSWORD" \
  --arg supervisorLogin "$E2E_SUPERVISOR_LOGIN_IDENTIFIER" \
  --arg supervisorPassword "$E2E_SUPERVISOR_PASSWORD" \
  --arg runKey "$RUN_KEY" \
  --arg courseSectionId "$COURSE_SECTION_ID" \
  --arg appointmentTimeSlotId "$SLOT_ID" \
  --arg studentEnrollment "$STUDENT_ENROLLMENT" \
  '(.values[] | select(.key == "baseUrl").value) = $baseUrl
   | (.values[] | select(.key == "loginIdentifier").value) = $login
   | (.values[] | select(.key == "password").value) = $password
   | (.values[] | select(.key == "supervisorLoginIdentifier").value) = $supervisorLogin
   | (.values[] | select(.key == "supervisorPassword").value) = $supervisorPassword
   | (.values[] | select(.key == "runKey").value) = $runKey
   | (.values[] | select(.key == "fixtureCourseSectionId").value) = $courseSectionId
   | (.values[] | select(.key == "fixtureAppointmentTimeSlotId").value) = $appointmentTimeSlotId
   | (.values[] | select(.key == "fixtureStudentEnrollment").value) = $studentEnrollment' \
  "$e2e_dir/postman/workflows/wash-supervisor-front-v1.environment.example.json" \
  > "$runtime_environment"

docker build -q -f "$script_dir/Dockerfile" -t "$image" "$e2e_dir" >/dev/null
docker run --rm --network host \
  --user "$(id -u):$(id -g)" \
  -e BFF_BASE_URL="$BFF_BASE_URL" \
  -e E2E_SUITE=wash-supervisor \
  -e E2E_ALLOW_MUTATIONS=isolated-wash-supervisor-fixture \
  -e E2E_POSTMAN_ENVIRONMENT=/run/secrets/postman-environment.json \
  -e E2E_POSTMAN_STATE=/run/state/postman-environment-after.json \
  -v "$runtime_environment:/run/secrets/postman-environment.json:ro" \
  -v "$runtime_state:/run/state/postman-environment-after.json" \
  -v "$report_dir:/reports" \
  "$image"

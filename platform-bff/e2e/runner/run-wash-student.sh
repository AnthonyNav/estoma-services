#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
e2e_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
platform_dir=$(CDPATH= cd -- "$e2e_dir/.." && pwd)
report_dir=${E2E_REPORT_DIR:-$platform_dir/target/e2e/wash-student}
fixture="$e2e_dir/fixtures/wash-student/fixture-db.sh"
image=${E2E_RUNNER_IMAGE:-platform-bff-e2e:local}

: "${BFF_BASE_URL:?BFF_BASE_URL is required}"
: "${E2E_LOGIN_IDENTIFIER:?E2E_LOGIN_IDENTIFIER is required}"
: "${E2E_PASSWORD:?E2E_PASSWORD is required}"
: "${E2E_PSQL_EXEC:?E2E_PSQL_EXEC is required}"
case "$E2E_LOGIN_IDENTIFIER" in e2e-*) ;; *) printf 'wash-student runner requires an e2e-* login identifier\n' >&2; exit 2 ;; esac
case "$E2E_LOGIN_IDENTIFIER" in *[!A-Za-z0-9._@+-]*) printf 'wash-student login identifier contains unsafe characters\n' >&2; exit 2 ;; esac

mkdir -p "$report_dir"
chmod 700 "$report_dir"
temp_dir=$(mktemp -d)
login_body="$temp_dir/login.json"
login_response="$temp_dir/login-response.json"
runtime_environment="$temp_dir/postman-environment.json"
runtime_state="$temp_dir/postman-environment-after.json"
fixture_ready=0
bootstrap_token=

: > "$runtime_state"
chmod 600 "$runtime_state"

finish() {
  status=$?
  trap - EXIT INT TERM
  cleanup_status=0
  if [ -n "$bootstrap_token" ]; then
    curl -sS --max-time 5 -o /dev/null -X POST \
      -H "Authorization: Bearer $bootstrap_token" \
      "$BFF_BASE_URL/api/v1/auth/logout" >/dev/null 2>&1 || true
    bootstrap_token=
  fi
  if [ -f "$runtime_state" ]; then
    chmod 600 "$runtime_state"
    runtime_token=$(jq -er '.values[] | select(.key == "runtimeAccessToken") | .value | select(length > 0)' \
      "$runtime_state" 2>/dev/null || true)
    if [ -n "$runtime_token" ]; then
      curl -sS --max-time 5 -o /dev/null -X POST \
        -H "Authorization: Bearer $runtime_token" \
        "$BFF_BASE_URL/api/v1/auth/logout" >/dev/null 2>&1 || true
      unset runtime_token
    fi
  fi
  if [ "$fixture_ready" -eq 1 ]; then
    E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$E2E_PSQL_EXEC" \
      E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" \
      E2E_APPOINTMENTS_DATABASE="${E2E_APPOINTMENTS_DATABASE:-appointments_db}" \
      "$fixture" cleanup || cleanup_status=$?
  fi
  rm -f -- "$login_body" "$login_response" "$runtime_environment" "$runtime_state"
  rmdir -- "$temp_dir" 2>/dev/null || true
  if [ "$cleanup_status" -ne 0 ]; then
    printf 'Fixture cleanup failed; preserve and inspect %s/fixture.env before another run.\n' "$report_dir" >&2
    exit "$cleanup_status"
  fi
  exit "$status"
}
trap finish EXIT INT TERM

umask 077
jq -n \
  --arg login "$E2E_LOGIN_IDENTIFIER" \
  --arg password "$E2E_PASSWORD" \
  '{loginType:"USERNAME", loginIdentifier:$login, password:$password, deviceName:"platform-bff-wash-student-bootstrap"}' \
  > "$login_body"
login_status=$(curl -sS -o "$login_response" -w '%{http_code}' \
  -H 'Content-Type: application/json' --data-binary "@$login_body" \
  "$BFF_BASE_URL/api/v1/auth/login")
[ "$login_status" = 200 ] || {
  printf 'synthetic fixture login failed with HTTP %s\n' "$login_status" >&2
  exit 1
}
account_id=$(jq -er '.accountId | select(test("^[0-9a-fA-F-]{36}$"))' "$login_response")
bootstrap_token=$(jq -er '.accessToken | select(length > 0)' "$login_response")

# The discovery session is closed; the Postman workflow proves its own login/session lifecycle.
curl -sS -o /dev/null -X POST -H "Authorization: Bearer $bootstrap_token" \
  "$BFF_BASE_URL/api/v1/auth/logout"
bootstrap_token=

E2E_ACCOUNT_ID="$account_id" E2E_LOGIN_IDENTIFIER="$E2E_LOGIN_IDENTIFIER" \
  E2E_REPORT_DIR="$report_dir" E2E_PSQL_EXEC="$E2E_PSQL_EXEC" \
  E2E_BFF_DATABASE="${E2E_BFF_DATABASE:-platform_bff_db}" \
  E2E_APPOINTMENTS_DATABASE="${E2E_APPOINTMENTS_DATABASE:-appointments_db}" \
  "$fixture" bootstrap
fixture_ready=1

RUN_KEY=$(sed -n 's/^RUN_KEY=//p' "$report_dir/fixture.env")
COURSE_SECTION_ID=$(sed -n 's/^COURSE_SECTION_ID=//p' "$report_dir/fixture.env")
SLOT_ID=$(sed -n 's/^SLOT_ID=//p' "$report_dir/fixture.env")
case "$RUN_KEY" in e2e-wash-[A-Za-z0-9-]*) ;; *) printf 'invalid runtime fixture manifest\n' >&2; exit 1 ;; esac
case "$RUN_KEY" in *[!A-Za-z0-9-]*) printf 'invalid runtime fixture manifest\n' >&2; exit 1 ;; esac
case "$COURSE_SECTION_ID:$SLOT_ID" in
  ????????-????-????-????-????????????:????????-????-????-????-????????????) ;;
  *) printf 'invalid runtime fixture identifiers\n' >&2; exit 1 ;;
esac
case "$COURSE_SECTION_ID$SLOT_ID" in *[!0-9A-Fa-f-]*) printf 'invalid runtime fixture identifiers\n' >&2; exit 1 ;; esac
jq \
  --arg baseUrl "$BFF_BASE_URL" \
  --arg login "$E2E_LOGIN_IDENTIFIER" \
  --arg password "$E2E_PASSWORD" \
  --arg runKey "$RUN_KEY" \
  --arg courseSectionId "$COURSE_SECTION_ID" \
  --arg appointmentTimeSlotId "$SLOT_ID" \
  '(.values[] | select(.key == "baseUrl").value) = $baseUrl
   | (.values[] | select(.key == "loginIdentifier").value) = $login
   | (.values[] | select(.key == "password").value) = $password
   | (.values[] | select(.key == "runKey").value) = $runKey
   | (.values[] | select(.key == "fixtureCourseSectionId").value) = $courseSectionId
   | (.values[] | select(.key == "fixtureAppointmentTimeSlotId").value) = $appointmentTimeSlotId' \
  "$e2e_dir/postman/workflows/wash-student-front-v1.environment.example.json" \
  > "$runtime_environment"

docker build -q -f "$script_dir/Dockerfile" -t "$image" "$e2e_dir" >/dev/null
docker run --rm --network host \
  --user "$(id -u):$(id -g)" \
  -e BFF_BASE_URL="$BFF_BASE_URL" \
  -e E2E_SUITE=wash-student \
  -e E2E_ALLOW_MUTATIONS=isolated-wash-student-fixture \
  -e E2E_POSTMAN_ENVIRONMENT=/run/secrets/postman-environment.json \
  -e E2E_POSTMAN_STATE=/run/state/postman-environment-after.json \
  -v "$runtime_environment:/run/secrets/postman-environment.json:ro" \
  -v "$runtime_state:/run/state/postman-environment-after.json" \
  -v "$report_dir:/reports" \
  "$image"

#!/bin/sh
set -eu

: "${BFF_BASE_URL:?BFF_BASE_URL is required}"

mkdir -p /reports

case "${E2E_SUITE:-smoke}" in
  smoke)
    exec newman run /opt/e2e/smoke.json \
      --environment /opt/e2e/environment.example.json \
      --env-var "baseUrl=${BFF_BASE_URL}" \
      --reporters cli,junit,json \
      --reporter-junit-export /reports/junit.xml \
      --reporter-json-export /reports/newman.json
    ;;
  wash-student)
    : "${E2E_ALLOW_MUTATIONS:?E2E_ALLOW_MUTATIONS is required for the wash-student suite}"
    [ "$E2E_ALLOW_MUTATIONS" = "isolated-wash-student-fixture" ] || {
      echo "E2E_ALLOW_MUTATIONS does not authorize the isolated wash-student workflow" >&2
      exit 2
    }
    : "${E2E_POSTMAN_ENVIRONMENT:?E2E_POSTMAN_ENVIRONMENT must point to the runtime-only environment file}"
    exec newman run /opt/e2e/wash-student.json \
      --environment "$E2E_POSTMAN_ENVIRONMENT" \
      --delay-request "${E2E_DELAY_MILLIS:-1000}" \
      --bail \
      --reporters cli,junit \
      --reporter-junit-export /reports/junit-wash-student.xml
    ;;
  *)
    echo "unsupported E2E_SUITE: $E2E_SUITE" >&2
    exit 2
    ;;
esac

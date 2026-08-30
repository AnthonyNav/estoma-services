#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
cleanup() {
  docker compose --profile run down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

docker compose --profile run up -d --build rabbitmq appointments-db wash-admin-db wash-execution-db authorization-db appointments wash-administration wash-execution authorization
docker compose --profile run run --build --rm runner --phase main
docker compose --profile run restart wash-execution
docker compose --profile run run --build --rm runner --phase restart

echo "PR4 distributed E2E: PASS"

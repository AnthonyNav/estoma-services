#!/bin/sh
set -eu

: "${BFF_BASE_URL:?BFF_BASE_URL is required}"

mkdir -p /reports
exec newman run /opt/e2e/collection.json \
  --environment /opt/e2e/environment.example.json \
  --env-var "baseUrl=${BFF_BASE_URL}" \
  --reporters cli,junit \
  --reporter-junit-export /reports/junit.xml

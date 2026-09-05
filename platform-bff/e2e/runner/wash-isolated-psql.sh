#!/bin/sh
# Deliberately fixed destination: never accept a namespace, pod or host override.
set -eu
database=${1:?database or --assert-isolated-wash required}
case "$database" in
  --assert-isolated-wash|authentication_db|platform_bff_db|appointments_db|wash_execution_db) ;;
  *) echo 'unsupported isolated database' >&2; exit 2 ;;
esac
remote_guard='set -eu
test "$(hostname)" = desktop
test "$(k3s kubectl get namespace wash-e2e -o jsonpath="{.metadata.labels.estoma\\.io/environment}")" = wash-e2e
test "$(k3s kubectl -n wash-e2e get pod postgres-0 -o jsonpath="{.metadata.labels.app}")" = postgres'
if [ "$database" = --assert-isolated-wash ]; then
  exec ssh -F /dev/null -o BatchMode=yes -o ConnectTimeout=8 anthony@100.66.139.125 "$remote_guard"
fi
exec ssh -F /dev/null -o BatchMode=yes -o ConnectTimeout=8 anthony@100.66.139.125 \
  "$remote_guard
k3s kubectl -n wash-e2e exec -i postgres-0 -- env PGOPTIONS='-c statement_timeout=5000 -c lock_timeout=3000' psql -X -v ON_ERROR_STOP=1 -U postgres -d '$database' -Atq"

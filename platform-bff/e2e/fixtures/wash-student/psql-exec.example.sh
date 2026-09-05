#!/bin/sh
set -eu

database=${1:?database is required}
: "${E2E_POSTGRES_DSN_PREFIX:?Set a private runtime DSN prefix ending before the database name}"

# This adapter is deliberately infrastructure-neutral. Keep the DSN and any password outside Git.
exec psql -X -qAt -v ON_ERROR_STOP=1 "${E2E_POSTGRES_DSN_PREFIX}${database}"

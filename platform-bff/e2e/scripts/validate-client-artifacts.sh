#!/bin/sh
# Keep the validation suite in a file instead of a large CI_SCRIPT environment value.
# Run from platform-bff; stop at the first failing check.
set -ex

node e2e/scripts/validate-openapi-postman.mjs
node e2e/scripts/validate-auth-workflow.mjs
node e2e/scripts/validate-wash-student-workflow.mjs
node e2e/scripts/generate-wash-supervisor-workflow.mjs --check
node e2e/scripts/validate-wash-supervisor-workflow.mjs
node e2e/scripts/generate-wash-supervisor-entry-workflow.mjs --check
node e2e/scripts/validate-wash-supervisor-entry-workflow.mjs
node e2e/scripts/generate-wash-supervisor-authorized-workflow.mjs --check
node e2e/scripts/validate-wash-supervisor-authorized-workflow.mjs
node e2e/scripts/generate-wash-exit-completion-workflow.mjs --check
node e2e/scripts/validate-wash-exit-completion-workflow.mjs
node --check e2e/runner/run-wash-exit-completion.mjs
sh -n e2e/runner/run-wash-exit-completion.sh
sh -n e2e/fixtures/wash-exit-completion/fixture-db.sh
sh -n e2e/scripts/test-wash-exit-completion-postgres.sh
sh e2e/scripts/test-wash-exit-completion-cleanup-retry.sh
node --check e2e/runner/run-wash-supervisor-authorized.mjs
node --check e2e/scripts/audit-wash-isolated-cleanup.mjs
node --check e2e/runner/run-reassignment-candidates.mjs
node e2e/scripts/test-reassignment-candidates-assertions.mjs
node e2e/scripts/test-reassignment-identity-bootstrap.mjs
sh -n e2e/fixtures/reassignment-candidates/fixture-db.sh
sh -n e2e/scripts/test-reassignment-candidates-postgres.sh
sh e2e/scripts/test-reassignment-candidates-fixture.sh
sh -n e2e/fixtures/wash-student/fixture-db.sh
sh -n e2e/fixtures/wash-student/psql-exec.example.sh
sh -n e2e/fixtures/wash-supervisor/fixture-db.sh
sh -n e2e/fixtures/wash-supervisor-entry/fixture-db.sh
sh -n e2e/runner/run-wash-student.sh
sh -n e2e/runner/run-wash-supervisor.sh
sh -n e2e/runner/run-wash-supervisor-entry.sh
sh -n e2e/runner/run.sh
sh e2e/scripts/test-wash-student-cleanup-retry.sh
sh e2e/scripts/test-wash-supervisor-cleanup-retry.sh
sh e2e/scripts/test-wash-supervisor-entry-cleanup-retry.sh
sh e2e/scripts/test-wash-supervisor-entry-runner.sh
sh -n e2e/runner/wash-isolated-psql.sh
sh -n e2e/fixtures/wash-supervisor-authorized/fixture-db.sh
sh e2e/scripts/test-wash-supervisor-authorized-cleanup-retry.sh
sh e2e/fixtures/isolated-identities/test-fixture.sh
sh e2e/fixtures/isolated-identities/test-cleanup-retry.sh

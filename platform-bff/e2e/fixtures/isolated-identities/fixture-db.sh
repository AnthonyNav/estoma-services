#!/bin/sh
set -eu

mode=${1:-}
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
platform_dir=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
report_dir=${E2E_REPORT_DIR:-$platform_dir/target/e2e/isolated-identities}
manifest=${E2E_IDENTITY_MANIFEST:-$report_dir/identity-fixture.env}
credentials=${E2E_IDENTITY_CREDENTIALS:-$report_dir/identity-credentials.json}
authentication_database=${E2E_AUTHENTICATION_DATABASE:-authentication_db}
bff_database=${E2E_BFF_DATABASE:-platform_bff_db}
hash_parameters='{"memoryKb":19456,"iterations":2,"parallelism":1,"saltLength":16,"hashLength":32}'

fail() {
  printf 'isolated-identities fixture: %s\n' "$*" >&2
  exit 1
}

require_psql() {
  : "${E2E_PSQL_EXEC:?E2E_PSQL_EXEC is required}"
  [ -x "$E2E_PSQL_EXEC" ] || fail "E2E_PSQL_EXEC is not executable"
}

assert_isolated() {
  "$E2E_PSQL_EXEC" --assert-isolated-wash >/dev/null
}

sql() {
  "$E2E_PSQL_EXEC" "$1"
}

new_uuid() {
  sed -n '1p' /proc/sys/kernel/random/uuid
}

new_password() {
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
}

manifest_value() {
  key=$1
  value=$(sed -n "s/^${key}=//p" "$manifest")
  [ -n "$value" ] || fail "manifest is missing $key"
  [ "$(printf '%s\n' "$value" | wc -l)" -eq 1 ] || fail "manifest repeats $key"
  printf '%s\n' "$value"
}

require_uuid() {
  case "$1" in
    *[!0-9A-Fa-f-]*|'') fail "$2 must be a UUID" ;;
    ????????-????-????-????-????????????) ;;
    *) fail "$2 must be a UUID" ;;
  esac
}

require_login() {
  case "$1" in e2e-*) ;; *) fail "$2 must begin with e2e-" ;; esac
  case "$1" in *[!A-Za-z0-9._@+-]*) fail "$2 contains unsafe characters" ;; esac
}

json_value() {
  key=$1
  node -e 'const fs=require("fs"); const d=JSON.parse(fs.readFileSync(process.argv[1],"utf8")); const v=d[process.argv[2]]; if(typeof v!=="string"||!v) process.exit(2); process.stdout.write(v)' "$credentials" "$key"
}

hash_password() {
  : "${E2E_PASSWORD_HASH_EXEC:?E2E_PASSWORD_HASH_EXEC is required for bootstrap}"
  [ -x "$E2E_PASSWORD_HASH_EXEC" ] || fail "E2E_PASSWORD_HASH_EXEC is not executable"
  encoded=$(printf '%s\n' "$1" | "$E2E_PASSWORD_HASH_EXEC")
  case "$encoded" in '$argon2id$'*) ;; *) fail "password helper did not return an Argon2id hash" ;; esac
  case "$encoded" in *"'"*|*'
'*) fail "password helper returned an unsafe hash" ;; esac
  [ "${#encoded}" -le 500 ] || fail "password helper returned an oversized hash"
  printf '%s\n' "$encoded"
}

create_state() {
  [ ! -e "$manifest" ] || fail "manifest already exists"
  [ ! -e "$credentials" ] || fail "credentials file already exists"
  mkdir -p "$report_dir"
  chmod 700 "$report_dir"
  umask 077
  student_account_id=$(new_uuid)
  student_person_id=$(new_uuid)
  supervisor_account_id=$(new_uuid)
  supervisor_person_id=$(new_uuid)
  suffix=$(printf '%s' "$student_account_id" | tr -d '-' | cut -c1-12)
  student_login="e2e-student-$suffix"
  supervisor_login="e2e-supervisor-$suffix"
  student_password=$(new_password)
  supervisor_password=$(new_password)
  {
    printf 'STUDENT_ACCOUNT_ID=%s\n' "$student_account_id"
    printf 'STUDENT_PERSON_ID=%s\n' "$student_person_id"
    printf 'STUDENT_LOGIN=%s\n' "$student_login"
    printf 'SUPERVISOR_ACCOUNT_ID=%s\n' "$supervisor_account_id"
    printf 'SUPERVISOR_PERSON_ID=%s\n' "$supervisor_person_id"
    printf 'SUPERVISOR_LOGIN=%s\n' "$supervisor_login"
  } > "$manifest"
  printf '{"studentAccountId":"%s","studentPersonId":"%s","studentLogin":"%s","studentPassword":"%s","supervisorAccountId":"%s","supervisorPersonId":"%s","supervisorLogin":"%s","supervisorPassword":"%s"}\n' \
    "$student_account_id" "$student_person_id" "$student_login" "$student_password" \
    "$supervisor_account_id" "$supervisor_person_id" "$supervisor_login" "$supervisor_password" > "$credentials"
  chmod 600 "$manifest" "$credentials"
}

load_state() {
  [ -f "$manifest" ] || fail "identity manifest is missing"
  [ -f "$credentials" ] || fail "identity credentials are missing"
  [ "$(stat -c %a "$manifest")" = 600 ] || fail "identity manifest must be mode 0600"
  [ "$(stat -c %a "$credentials")" = 600 ] || fail "identity credentials must be mode 0600"
  STUDENT_ACCOUNT_ID=$(manifest_value STUDENT_ACCOUNT_ID)
  STUDENT_PERSON_ID=$(manifest_value STUDENT_PERSON_ID)
  STUDENT_LOGIN=$(manifest_value STUDENT_LOGIN)
  SUPERVISOR_ACCOUNT_ID=$(manifest_value SUPERVISOR_ACCOUNT_ID)
  SUPERVISOR_PERSON_ID=$(manifest_value SUPERVISOR_PERSON_ID)
  SUPERVISOR_LOGIN=$(manifest_value SUPERVISOR_LOGIN)
  require_uuid "$STUDENT_ACCOUNT_ID" STUDENT_ACCOUNT_ID
  require_uuid "$STUDENT_PERSON_ID" STUDENT_PERSON_ID
  require_uuid "$SUPERVISOR_ACCOUNT_ID" SUPERVISOR_ACCOUNT_ID
  require_uuid "$SUPERVISOR_PERSON_ID" SUPERVISOR_PERSON_ID
  require_login "$STUDENT_LOGIN" STUDENT_LOGIN
  require_login "$SUPERVISOR_LOGIN" SUPERVISOR_LOGIN
  [ "$STUDENT_ACCOUNT_ID" != "$SUPERVISOR_ACCOUNT_ID" ] || fail "account IDs must differ"
  [ "$STUDENT_PERSON_ID" != "$SUPERVISOR_PERSON_ID" ] || fail "person IDs must differ"
  [ "$STUDENT_LOGIN" = "$(json_value studentLogin)" ] || fail "student credential login mismatch"
  [ "$SUPERVISOR_LOGIN" = "$(json_value supervisorLogin)" ] || fail "supervisor credential login mismatch"
  [ "$STUDENT_ACCOUNT_ID" = "$(json_value studentAccountId)" ] || fail "student credential account mismatch"
  [ "$STUDENT_PERSON_ID" = "$(json_value studentPersonId)" ] || fail "student credential person mismatch"
  [ "$SUPERVISOR_ACCOUNT_ID" = "$(json_value supervisorAccountId)" ] || fail "supervisor credential account mismatch"
  [ "$SUPERVISOR_PERSON_ID" = "$(json_value supervisorPersonId)" ] || fail "supervisor credential person mismatch"
  STUDENT_PASSWORD=$(json_value studentPassword)
  SUPERVISOR_PASSWORD=$(json_value supervisorPassword)
}

bootstrap() {
  require_psql
  if [ ! -e "$manifest" ]; then create_state; fi
  load_state
  student_hash=$(hash_password "$STUDENT_PASSWORD")
  supervisor_hash=$(hash_password "$SUPERVISOR_PASSWORD")

  assert_isolated
  sql "$authentication_database" >/dev/null <<SQL
BEGIN;
DO \$guard\$
BEGIN
  IF EXISTS (SELECT 1 FROM accounts WHERE account_id NOT IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))
     OR EXISTS (SELECT 1 FROM person_status_projection WHERE person_id NOT IN ('$STUDENT_PERSON_ID'::uuid,'$SUPERVISOR_PERSON_ID'::uuid)) THEN
    RAISE EXCEPTION 'authentication database is not a fresh isolated identity fixture';
  END IF;
  IF EXISTS (SELECT 1 FROM person_status_projection
      WHERE person_id IN ('$STUDENT_PERSON_ID'::uuid,'$SUPERVISOR_PERSON_ID'::uuid)
        AND (status<>'ACTIVE' OR source_version<>1)) THEN
    RAISE EXCEPTION 'authentication person projection differs from fixture contract';
  END IF;
  IF EXISTS (SELECT 1 FROM accounts WHERE
      (account_id='$STUDENT_ACCOUNT_ID'::uuid AND (person_id<>'$STUDENT_PERSON_ID'::uuid OR login_type<>'USERNAME' OR login_identifier<>'$STUDENT_LOGIN' OR login_identifier_normalized<>'$STUDENT_LOGIN' OR status<>'ACTIVE')) OR
      (account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND (person_id<>'$SUPERVISOR_PERSON_ID'::uuid OR login_type<>'USERNAME' OR login_identifier<>'$SUPERVISOR_LOGIN' OR login_identifier_normalized<>'$SUPERVISOR_LOGIN' OR status<>'ACTIVE'))) THEN
    RAISE EXCEPTION 'authentication identity differs from manifest';
  END IF;
  IF EXISTS (SELECT 1 FROM account_credentials WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid)
      AND (hash_algorithm<>'ARGON2ID' OR hash_parameters<>'$hash_parameters'::jsonb OR must_change_password OR temporary_password_expires_at IS NOT NULL OR temporary_password_consumed_at IS NOT NULL)) THEN
    RAISE EXCEPTION 'authentication credential differs from fixture contract';
  END IF;
END \$guard\$;
INSERT INTO accounts(account_id,person_id,login_type,login_identifier,login_identifier_normalized,status,failed_attempts,locked_until,version,created_at,updated_at,deactivated_at,deactivation_reason_code,last_recovery_requested_at)
VALUES
('$STUDENT_ACCOUNT_ID','$STUDENT_PERSON_ID','USERNAME','$STUDENT_LOGIN','$STUDENT_LOGIN','ACTIVE',0,NULL,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL,NULL,NULL),
('$SUPERVISOR_ACCOUNT_ID','$SUPERVISOR_PERSON_ID','USERNAME','$SUPERVISOR_LOGIN','$SUPERVISOR_LOGIN','ACTIVE',0,NULL,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL,NULL,NULL)
ON CONFLICT(account_id) DO NOTHING;
INSERT INTO account_credentials(account_id,password_hash,hash_algorithm,hash_parameters,password_changed_at,must_change_password,temporary_password_expires_at,temporary_password_consumed_at,credential_version)
VALUES
('$STUDENT_ACCOUNT_ID','$student_hash','ARGON2ID','$hash_parameters',CURRENT_TIMESTAMP,false,NULL,NULL,1),
('$SUPERVISOR_ACCOUNT_ID','$supervisor_hash','ARGON2ID','$hash_parameters',CURRENT_TIMESTAMP,false,NULL,NULL,1)
ON CONFLICT(account_id) DO NOTHING;
INSERT INTO person_status_projection(person_id,status,source_version,updated_at)
VALUES ('$STUDENT_PERSON_ID','ACTIVE',1,CURRENT_TIMESTAMP),('$SUPERVISOR_PERSON_ID','ACTIVE',1,CURRENT_TIMESTAMP)
ON CONFLICT(person_id) DO NOTHING;
COMMIT;
SQL

  assert_isolated
  sql "$bff_database" >/dev/null <<SQL
BEGIN;
DO \$guard\$
BEGIN
  IF EXISTS (SELECT 1 FROM account_presentation_read_model WHERE account_id NOT IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))
     OR EXISTS (SELECT 1 FROM person_presentation_read_model WHERE person_id NOT IN ('$STUDENT_PERSON_ID'::uuid,'$SUPERVISOR_PERSON_ID'::uuid)) THEN
    RAISE EXCEPTION 'BFF database is not a fresh isolated identity fixture';
  END IF;
  IF EXISTS (SELECT 1 FROM person_presentation_read_model
      WHERE person_id IN ('$STUDENT_PERSON_ID'::uuid,'$SUPERVISOR_PERSON_ID'::uuid)
        AND (status<>'ACTIVE' OR source_version<>1)) THEN
    RAISE EXCEPTION 'BFF person presentation differs from fixture contract';
  END IF;
  IF EXISTS (SELECT 1 FROM account_presentation_read_model WHERE
      (account_id='$STUDENT_ACCOUNT_ID'::uuid AND (person_id<>'$STUDENT_PERSON_ID'::uuid OR login_type<>'USERNAME' OR login_identifier<>'$STUDENT_LOGIN' OR account_status<>'ACTIVE')) OR
      (account_id='$SUPERVISOR_ACCOUNT_ID'::uuid AND (person_id<>'$SUPERVISOR_PERSON_ID'::uuid OR login_type<>'USERNAME' OR login_identifier<>'$SUPERVISOR_LOGIN' OR account_status<>'ACTIVE'))) THEN
    RAISE EXCEPTION 'BFF account presentation differs from manifest';
  END IF;
END \$guard\$;
INSERT INTO account_presentation_read_model(account_id,person_id,login_type,login_identifier,account_status,source_version,updated_at)
VALUES
('$STUDENT_ACCOUNT_ID','$STUDENT_PERSON_ID','USERNAME','$STUDENT_LOGIN','ACTIVE',1,CURRENT_TIMESTAMP),
('$SUPERVISOR_ACCOUNT_ID','$SUPERVISOR_PERSON_ID','USERNAME','$SUPERVISOR_LOGIN','ACTIVE',1,CURRENT_TIMESTAMP)
ON CONFLICT(account_id) DO NOTHING;
INSERT INTO person_presentation_read_model(person_id,first_name,paternal_surname,maternal_surname,institutional_email,status,source_version,updated_at)
VALUES
('$STUDENT_PERSON_ID','E2E Student','Isolated',NULL,'$STUDENT_LOGIN@invalid','ACTIVE',1,CURRENT_TIMESTAMP),
('$SUPERVISOR_PERSON_ID','E2E Supervisor','Isolated',NULL,'$SUPERVISOR_LOGIN@invalid','ACTIVE',1,CURRENT_TIMESTAMP)
ON CONFLICT(person_id) DO NOTHING;
COMMIT;
SQL
  unset STUDENT_PASSWORD SUPERVISOR_PASSWORD student_hash supervisor_hash encoded
  printf 'Isolated identities ready. Manifest: %s Credentials: %s\n' "$manifest" "$credentials"
}

cleanup() {
  require_psql
  load_state
  session_ids=$(sql "$authentication_database" <<SQL
SELECT COALESCE(string_agg(quote_literal(session_id::text),',' ORDER BY session_id),'')
FROM sessions WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid);
SQL
  )
  case "$session_ids" in *[!0-9a-fA-F,\'-]*) fail "unsafe session identity result" ;; esac
  if [ -n "$session_ids" ]; then session_array="ARRAY[$session_ids]::uuid[]"; else session_array="ARRAY[]::uuid[]"; fi

  assert_isolated
  sql "$bff_database" >/dev/null <<SQL
BEGIN;
DO \$guard\$
BEGIN
  IF EXISTS (SELECT 1 FROM authorization_role_projection WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))
     OR EXISTS (SELECT 1 FROM authorization_access_projection WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))
     OR EXISTS (SELECT 1 FROM student_academic_profile_read_model WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))
     OR EXISTS (SELECT 1 FROM student_enrollment_read_model WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))
     OR EXISTS (SELECT 1 FROM appointment_read_model WHERE student_account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))
     OR EXISTS (SELECT 1 FROM appointment_availability_refresh WHERE student_account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))
     OR EXISTS (SELECT 1 FROM appointment_availability_read_model WHERE student_account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))
     OR EXISTS (SELECT 1 FROM client_operations WHERE actor_account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid)) THEN
    RAISE EXCEPTION 'dependent BFF wash fixture rows remain';
  END IF;
  IF (SELECT count(*) FROM session_revocations WHERE session_id=ANY($session_array)) <>
     (SELECT count(*) FROM unnest($session_array)) THEN
    RAISE EXCEPTION 'not every synthetic session has converged as revoked';
  END IF;
END \$guard\$;
DELETE FROM inbox_messages WHERE message_id IN (SELECT source_message_id FROM session_revocations WHERE session_id=ANY($session_array));
DELETE FROM session_revocations WHERE session_id=ANY($session_array);
DELETE FROM account_session_revocation_cutoffs WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid);
DELETE FROM person_session_revocation_cutoffs WHERE person_id IN ('$STUDENT_PERSON_ID'::uuid,'$SUPERVISOR_PERSON_ID'::uuid);
DELETE FROM account_presentation_read_model WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid);
DELETE FROM person_presentation_read_model WHERE person_id IN ('$STUDENT_PERSON_ID'::uuid,'$SUPERVISOR_PERSON_ID'::uuid);
COMMIT;
SQL

  assert_isolated
  sql "$authentication_database" >/dev/null <<SQL
BEGIN;
DO \$guard\$
BEGIN
  IF EXISTS (SELECT 1 FROM sessions WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid) AND revoked_at IS NULL)
     OR EXISTS (SELECT 1 FROM outbox_messages WHERE aggregate_id=ANY($session_array) AND status IN ('PENDING','PROCESSING'))
     OR EXISTS (SELECT 1 FROM password_recovery_requests WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))
     OR EXISTS (SELECT 1 FROM security_rpc_idempotency_records WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))
     OR EXISTS (SELECT 1 FROM account_status_history WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid)) THEN
    RAISE EXCEPTION 'dependent Authentication rows remain';
  END IF;
END \$guard\$;
DELETE FROM outbox_messages WHERE aggregate_id=ANY($session_array);
DELETE FROM sessions WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid);
DELETE FROM student_number_login_sync_projection WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid);
DELETE FROM account_credentials WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid);
DELETE FROM accounts WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid);
DELETE FROM person_status_projection WHERE person_id IN ('$STUDENT_PERSON_ID'::uuid,'$SUPERVISOR_PERSON_ID'::uuid);
COMMIT;
SQL

  audit_auth=$(sql "$authentication_database" <<SQL
SELECT (SELECT count(*) FROM accounts WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))+
       (SELECT count(*) FROM account_credentials WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))+
       (SELECT count(*) FROM person_status_projection WHERE person_id IN ('$STUDENT_PERSON_ID'::uuid,'$SUPERVISOR_PERSON_ID'::uuid))+
       (SELECT count(*) FROM sessions WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))+
       (SELECT count(*) FROM outbox_messages WHERE aggregate_id=ANY($session_array));
SQL
  )
  audit_bff=$(sql "$bff_database" <<SQL
SELECT (SELECT count(*) FROM account_presentation_read_model WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))+
       (SELECT count(*) FROM person_presentation_read_model WHERE person_id IN ('$STUDENT_PERSON_ID'::uuid,'$SUPERVISOR_PERSON_ID'::uuid))+
       (SELECT count(*) FROM session_revocations WHERE session_id=ANY($session_array))+
       (SELECT count(*) FROM account_session_revocation_cutoffs WHERE account_id IN ('$STUDENT_ACCOUNT_ID'::uuid,'$SUPERVISOR_ACCOUNT_ID'::uuid))+
       (SELECT count(*) FROM person_session_revocation_cutoffs WHERE person_id IN ('$STUDENT_PERSON_ID'::uuid,'$SUPERVISOR_PERSON_ID'::uuid));
SQL
  )
  [ "$audit_auth:$audit_bff" = 0:0 ] || fail "post-cleanup identity audit failed"
  rm -f -- "$credentials" "$manifest"
  printf 'Isolated identities cleaned.\n'
}

case "$mode" in
  bootstrap) bootstrap ;;
  cleanup) cleanup ;;
  *) fail "usage: $0 bootstrap|cleanup" ;;
esac

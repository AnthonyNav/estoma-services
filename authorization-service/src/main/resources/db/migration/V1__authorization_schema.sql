CREATE TYPE catalog_status AS ENUM ('ACTIVE', 'INACTIVE');
CREATE TYPE login_type AS ENUM ('MATRICULA', 'USERNAME');
CREATE TYPE projected_status AS ENUM ('ACTIVE', 'INACTIVE');
CREATE TYPE actor_type AS ENUM ('ACCOUNT', 'SYSTEM');
CREATE TYPE system_access_status AS ENUM ('ACTIVE', 'SUSPENDED', 'REVOKED');
CREATE TYPE inbox_status AS ENUM ('DEFERRED', 'APPLIED');
CREATE TYPE idempotency_status AS ENUM ('DEFERRED', 'COMPLETED', 'FAILED');
CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED');
CREATE TYPE recovery_scope AS ENUM ('FULL', 'ENTITY');
CREATE TYPE recovery_job_status AS ENUM ('PENDING', 'WAITING_RESULT', 'COMPLETED', 'FAILED');

CREATE TABLE systems (
  system_id uuid PRIMARY KEY,
  code varchar(80) NOT NULL UNIQUE,
  name varchar(160) NOT NULL,
  status catalog_status NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_systems_code CHECK (btrim(code) <> ''),
  CONSTRAINT ck_systems_name CHECK (btrim(name) <> '')
);

CREATE TABLE roles (
  role_id uuid PRIMARY KEY,
  code varchar(100) NOT NULL UNIQUE,
  name varchar(160) NOT NULL,
  status catalog_status NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_roles_code CHECK (btrim(code) <> ''),
  CONSTRAINT ck_roles_name CHECK (btrim(name) <> '')
);

CREATE TABLE permissions (
  permission_id uuid PRIMARY KEY,
  system_id uuid NOT NULL REFERENCES systems(system_id),
  code varchar(160) NOT NULL,
  name varchar(160) NOT NULL,
  status catalog_status NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT uq_permissions_system_code UNIQUE (system_id, code),
  CONSTRAINT ck_permissions_code CHECK (btrim(code) <> ''),
  CONSTRAINT ck_permissions_name CHECK (btrim(name) <> '')
);

CREATE TABLE role_systems (
  role_id uuid NOT NULL REFERENCES roles(role_id),
  system_id uuid NOT NULL REFERENCES systems(system_id),
  assigned_at timestamptz NOT NULL,
  PRIMARY KEY (role_id, system_id)
);

CREATE TABLE role_permissions (
  role_id uuid NOT NULL REFERENCES roles(role_id),
  permission_id uuid NOT NULL REFERENCES permissions(permission_id),
  assigned_at timestamptz NOT NULL,
  PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE account_roles (
  account_id uuid PRIMARY KEY,
  role_id uuid NOT NULL REFERENCES roles(role_id),
  version integer NOT NULL DEFAULT 1,
  assigned_by_account_id uuid,
  assigned_by_actor_type actor_type NOT NULL,
  assigned_by_actor_service varchar(80),
  changed_by_account_id uuid,
  changed_by_actor_type actor_type,
  changed_by_actor_service varchar(80),
  assigned_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_account_roles_version CHECK (version > 0),
  CONSTRAINT ck_account_roles_assigned_actor CHECK (
    (assigned_by_actor_type = 'ACCOUNT' AND assigned_by_account_id IS NOT NULL AND assigned_by_actor_service IS NULL)
    OR (assigned_by_actor_type = 'SYSTEM' AND assigned_by_account_id IS NULL
      AND assigned_by_actor_service IS NOT NULL AND btrim(assigned_by_actor_service) <> '')
  ),
  CONSTRAINT ck_account_roles_changed_actor CHECK (
    (changed_by_actor_type IS NULL AND changed_by_account_id IS NULL AND changed_by_actor_service IS NULL)
    OR (changed_by_actor_type = 'ACCOUNT' AND changed_by_account_id IS NOT NULL AND changed_by_actor_service IS NULL)
    OR (changed_by_actor_type = 'SYSTEM' AND changed_by_account_id IS NULL
      AND changed_by_actor_service IS NOT NULL AND btrim(changed_by_actor_service) <> '')
  ),
  CONSTRAINT ck_account_roles_changed_actor_for_version CHECK (
    version = 1 OR changed_by_actor_type IS NOT NULL
  )
);

CREATE TABLE system_accesses (
  system_access_id uuid PRIMARY KEY,
  account_id uuid NOT NULL,
  system_id uuid NOT NULL REFERENCES systems(system_id),
  status system_access_status NOT NULL,
  reason_code varchar(40),
  reason_detail varchar(500),
  version integer NOT NULL DEFAULT 1,
  granted_by_account_id uuid,
  granted_by_actor_type actor_type NOT NULL,
  granted_by_actor_service varchar(80),
  suspended_by_account_id uuid,
  suspended_by_actor_type actor_type,
  suspended_by_actor_service varchar(80),
  revoked_by_account_id uuid,
  revoked_by_actor_type actor_type,
  revoked_by_actor_service varchar(80),
  restored_by_account_id uuid,
  restored_by_actor_type actor_type,
  restored_by_actor_service varchar(80),
  granted_at timestamptz NOT NULL,
  suspended_at timestamptz,
  revoked_at timestamptz,
  updated_at timestamptz NOT NULL,
  CONSTRAINT uq_system_accesses_account_system UNIQUE (account_id, system_id),
  CONSTRAINT ck_system_accesses_version CHECK (version > 0),
  CONSTRAINT ck_system_accesses_reason CHECK (
    (status = 'ACTIVE' AND reason_code IS NULL AND reason_detail IS NULL)
    OR (status IN ('SUSPENDED', 'REVOKED') AND reason_code IS NOT NULL)
  ),
  CONSTRAINT ck_system_accesses_reason_code CHECK (
    reason_code IS NULL OR reason_code IN ('ADMINISTRATIVE', 'ROLE_CHANGED', 'SECURITY', 'OTHER')
  ),
  CONSTRAINT ck_system_accesses_reason_detail CHECK (
    (reason_code = 'OTHER' AND reason_detail IS NOT NULL AND btrim(reason_detail) <> '')
    OR reason_code IS DISTINCT FROM 'OTHER'
  ),
  CONSTRAINT ck_system_accesses_role_changed_reason CHECK (
    reason_code IS DISTINCT FROM 'ROLE_CHANGED' OR status = 'SUSPENDED'
  ),
  CONSTRAINT ck_system_accesses_granted_actor CHECK (
    (granted_by_actor_type = 'ACCOUNT' AND granted_by_account_id IS NOT NULL AND granted_by_actor_service IS NULL)
    OR (granted_by_actor_type = 'SYSTEM' AND granted_by_account_id IS NULL
      AND granted_by_actor_service IS NOT NULL AND btrim(granted_by_actor_service) <> '')
  ),
  CONSTRAINT ck_system_accesses_suspended_actor CHECK (
    (suspended_by_actor_type IS NULL AND suspended_by_account_id IS NULL AND suspended_by_actor_service IS NULL)
    OR (suspended_by_actor_type = 'ACCOUNT' AND suspended_by_account_id IS NOT NULL AND suspended_by_actor_service IS NULL)
    OR (suspended_by_actor_type = 'SYSTEM' AND suspended_by_account_id IS NULL
      AND suspended_by_actor_service IS NOT NULL AND btrim(suspended_by_actor_service) <> '')
  ),
  CONSTRAINT ck_system_accesses_revoked_actor CHECK (
    (revoked_by_actor_type IS NULL AND revoked_by_account_id IS NULL AND revoked_by_actor_service IS NULL)
    OR (revoked_by_actor_type = 'ACCOUNT' AND revoked_by_account_id IS NOT NULL AND revoked_by_actor_service IS NULL)
    OR (revoked_by_actor_type = 'SYSTEM' AND revoked_by_account_id IS NULL
      AND revoked_by_actor_service IS NOT NULL AND btrim(revoked_by_actor_service) <> '')
  ),
  CONSTRAINT ck_system_accesses_restored_actor CHECK (
    (restored_by_actor_type IS NULL AND restored_by_account_id IS NULL AND restored_by_actor_service IS NULL)
    OR (restored_by_actor_type = 'ACCOUNT' AND restored_by_account_id IS NOT NULL AND restored_by_actor_service IS NULL)
    OR (restored_by_actor_type = 'SYSTEM' AND restored_by_account_id IS NULL
      AND restored_by_actor_service IS NOT NULL AND btrim(restored_by_actor_service) <> '')
  ),
  CONSTRAINT ck_system_accesses_suspended_transition CHECK (
    status <> 'SUSPENDED'
    OR (suspended_at IS NOT NULL AND suspended_by_actor_type IS NOT NULL)
  ),
  CONSTRAINT ck_system_accesses_revoked_transition CHECK (
    status <> 'REVOKED'
    OR (revoked_at IS NOT NULL AND revoked_by_actor_type IS NOT NULL)
  ),
  CONSTRAINT ck_system_accesses_restored_transition CHECK (
    status <> 'ACTIVE'
    OR version = 1
    OR restored_by_actor_type IS NOT NULL
  )
);

CREATE INDEX ix_system_accesses_account ON system_accesses (account_id);
CREATE INDEX ix_system_accesses_status ON system_accesses (status);

CREATE TABLE account_status_projection (
  account_id uuid PRIMARY KEY,
  person_id uuid NOT NULL,
  login_type login_type NOT NULL,
  status projected_status NOT NULL,
  source_version integer NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_account_projection_version CHECK (source_version > 0)
);

CREATE TABLE person_status_projection (
  person_id uuid PRIMARY KEY,
  status projected_status NOT NULL,
  source_version integer NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT ck_person_projection_version CHECK (source_version > 0)
);

CREATE TABLE academic_eligibility_projection (
  eligibility_id uuid NOT NULL,
  student_academic_profile_id uuid NOT NULL,
  system_code varchar(80) NOT NULL,
  account_id uuid NOT NULL,
  eligible boolean NOT NULL,
  policy_version integer NOT NULL,
  eligibility_version integer NOT NULL,
  academic_profile_version integer NOT NULL,
  pending_reason varchar(40),
  evaluated_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (student_academic_profile_id, system_code),
  CONSTRAINT ck_eligibility_versions CHECK (
    policy_version > 0 AND eligibility_version > 0 AND academic_profile_version > 0
  ),
  CONSTRAINT ck_eligibility_pending_reason CHECK (
    pending_reason IS NULL OR pending_reason IN ('EVENT_PENDING', 'PROJECTION_RECONCILIATION')
  )
);

CREATE INDEX ix_eligibility_account_system
  ON academic_eligibility_projection (account_id, system_code);

CREATE TABLE command_idempotency_records (
  producer varchar(120) NOT NULL,
  operation_type varchar(120) NOT NULL,
  idempotency_key varchar(200) NOT NULL,
  request_fingerprint varchar(200) NOT NULL,
  status idempotency_status NOT NULL,
  result_status varchar(20),
  result_data jsonb,
  error_code varchar(120),
  error_details jsonb,
  aggregate_id uuid,
  aggregate_version integer,
  import_id uuid,
  plan_item_id uuid,
  execution_generation integer,
  pending_reason varchar(80),
  created_at timestamptz NOT NULL,
  completed_at timestamptz,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (producer, operation_type, idempotency_key),
  CONSTRAINT ck_idempotency_key_not_blank CHECK (btrim(idempotency_key) <> ''),
  CONSTRAINT ck_idempotency_fingerprint_not_blank CHECK (btrim(request_fingerprint) <> ''),
  CONSTRAINT ck_idempotency_academic_metadata CHECK (
    (import_id IS NULL AND plan_item_id IS NULL AND execution_generation IS NULL)
    OR (import_id IS NOT NULL AND plan_item_id IS NOT NULL AND execution_generation IS NOT NULL AND execution_generation > 0)
  ),
  CONSTRAINT ck_idempotency_pending_reason CHECK (
    pending_reason IS NULL OR pending_reason IN ('EVENT_PENDING', 'PROJECTION_RECONCILIATION')
  ),
  CONSTRAINT ck_idempotency_academic_deferred CHECK (
    status <> 'DEFERRED' OR producer <> 'academic'
    OR (
      import_id IS NOT NULL
      AND plan_item_id IS NOT NULL
      AND execution_generation IS NOT NULL
      AND execution_generation > 0
      AND pending_reason IS NOT NULL
    )
  ),
  CONSTRAINT ck_idempotency_state_shape CHECK (
    (status = 'DEFERRED' AND completed_at IS NULL AND result_status IS NULL)
    OR (
      status = 'COMPLETED'
      AND completed_at IS NOT NULL
      AND result_status IS NOT NULL
      AND (
        (result_status = 'SUCCEEDED' AND result_data IS NOT NULL)
        OR (result_status = 'REJECTED' AND error_code IS NOT NULL AND btrim(error_code) <> '')
      )
    )
    OR (
      status = 'FAILED'
      AND completed_at IS NOT NULL
      AND result_status IS NOT NULL
      AND result_status = 'FAILED'
      AND error_code IS NOT NULL
      AND btrim(error_code) <> ''
    )
  )
);

CREATE INDEX ix_idempotency_academic_generation
  ON command_idempotency_records (producer, import_id, plan_item_id, execution_generation);
CREATE INDEX ix_idempotency_aggregate ON command_idempotency_records (aggregate_id);

CREATE TABLE authorization_audit_entries (
  audit_id uuid PRIMARY KEY,
  source_message_id uuid NOT NULL,
  source_producer varchar(120) NOT NULL,
  aggregate_type varchar(80) NOT NULL,
  aggregate_id uuid,
  aggregate_version integer,
  action varchar(100) NOT NULL,
  actor_type actor_type NOT NULL,
  actor_account_id uuid,
  actor_service varchar(80),
  business_initiator_account_id uuid,
  business_import_id uuid,
  correlation_id uuid NOT NULL,
  causation_id uuid,
  operation_id uuid,
  idempotency_key varchar(200),
  result varchar(20) NOT NULL,
  reason_code varchar(80),
  reason_detail varchar(500),
  occurred_at timestamptz NOT NULL,
  CONSTRAINT ck_audit_actor CHECK (
    (actor_type = 'ACCOUNT' AND actor_account_id IS NOT NULL AND actor_service IS NULL)
    OR (actor_type = 'SYSTEM' AND actor_account_id IS NULL
      AND actor_service IS NOT NULL AND btrim(actor_service) <> '')
  )
);

CREATE INDEX ix_audit_aggregate ON authorization_audit_entries (aggregate_type, aggregate_id, occurred_at);
CREATE INDEX ix_audit_correlation ON authorization_audit_entries (correlation_id);
CREATE INDEX ix_audit_source_message ON authorization_audit_entries (source_message_id);

CREATE TABLE processed_messages (
  consumer_name varchar(120) NOT NULL,
  message_id uuid NOT NULL,
  status inbox_status NOT NULL,
  message_type varchar(160) NOT NULL,
  message_version integer NOT NULL,
  message_kind varchar(20) NOT NULL,
  producer varchar(120) NOT NULL,
  correlation_id uuid NOT NULL,
  causation_id uuid,
  operation_id uuid,
  idempotency_key varchar(200),
  aggregate_id uuid,
  aggregate_version integer,
  actor jsonb,
  payload jsonb NOT NULL,
  received_at timestamptz NOT NULL,
  occurred_at timestamptz NOT NULL,
  applied_at timestamptz,
  deferred_reason varchar(200),
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (consumer_name, message_id),
  CONSTRAINT ck_processed_message_version CHECK (message_version > 0),
  CONSTRAINT ck_processed_message_kind CHECK (message_kind IN ('COMMAND', 'EVENT', 'RESULT')),
  CONSTRAINT ck_processed_message_state CHECK (
    (status = 'DEFERRED' AND deferred_reason IS NOT NULL AND applied_at IS NULL)
    OR (status = 'APPLIED' AND deferred_reason IS NULL AND applied_at IS NOT NULL)
  )
);

CREATE INDEX ix_processed_messages_consumer_status
  ON processed_messages (consumer_name, status);
CREATE INDEX ix_processed_messages_deferred_aggregate
  ON processed_messages (consumer_name, status, aggregate_id, aggregate_version);

CREATE TABLE outbox_messages (
  message_id uuid PRIMARY KEY,
  message_type varchar(160) NOT NULL,
  message_version integer NOT NULL,
  message_kind varchar(20) NOT NULL,
  producer varchar(120) NOT NULL,
  routing_key varchar(200) NOT NULL,
  operation_id uuid,
  idempotency_key varchar(200),
  actor jsonb,
  aggregate_id uuid,
  aggregate_version integer,
  correlation_id uuid NOT NULL,
  causation_id uuid,
  payload jsonb NOT NULL,
  status outbox_status NOT NULL DEFAULT 'PENDING',
  attempts integer NOT NULL DEFAULT 0,
  occurred_at timestamptz NOT NULL,
  available_at timestamptz NOT NULL,
  published_at timestamptz,
  last_error_code varchar(120),
  last_error_detail varchar(1000),
  claim_token uuid,
  claimed_at timestamptz,
  lease_until timestamptz,
  CONSTRAINT ck_outbox_message_version CHECK (message_version > 0),
  CONSTRAINT ck_outbox_message_kind CHECK (message_kind IN ('EVENT', 'RESULT', 'QUERY')),
  CONSTRAINT ck_outbox_producer CHECK (producer = 'authorization'),
  CONSTRAINT ck_outbox_idempotency_key CHECK (
    idempotency_key IS NULL OR btrim(idempotency_key) <> ''
  ),
  CONSTRAINT ck_outbox_kind_metadata CHECK (
    (message_kind = 'EVENT'
      AND aggregate_id IS NOT NULL
      AND aggregate_version IS NOT NULL
      AND aggregate_version > 0
      AND causation_id IS NOT NULL
      AND actor IS NOT NULL)
    OR (message_kind = 'RESULT'
      AND causation_id IS NOT NULL
      AND operation_id IS NOT NULL
      AND actor IS NOT NULL)
    OR (message_kind = 'QUERY'
      AND operation_id IS NOT NULL)
  ),
  CONSTRAINT ck_outbox_command_result_idempotency CHECK (
    message_kind <> 'RESULT'
    OR message_type NOT IN (
      'AssignAccountRoleResult',
      'ChangeAccountRoleResult',
      'GrantSystemAccessResult',
      'SuspendSystemAccessResult',
      'RevokeSystemAccessResult',
      'RestoreSystemAccessResult'
    )
    OR idempotency_key IS NOT NULL
  ),
  CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
  CONSTRAINT ck_outbox_processing_claim CHECK (
    (status = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (status <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL)
  ),
  CONSTRAINT ck_outbox_published_at CHECK (
    (status = 'PUBLISHED' AND published_at IS NOT NULL) OR status <> 'PUBLISHED'
  )
);

CREATE INDEX ix_outbox_pending ON outbox_messages (status, available_at, occurred_at)
  WHERE status = 'PENDING';
CREATE INDEX ix_outbox_processing_lease ON outbox_messages (status, lease_until)
  WHERE status = 'PROCESSING';

CREATE TABLE projection_recovery_jobs (
  recovery_job_id uuid PRIMARY KEY,
  projection_type varchar(80) NOT NULL,
  scope recovery_scope NOT NULL,
  entity_key varchar(300),
  status recovery_job_status NOT NULL,
  cursor varchar(1000),
  query_attempt integer NOT NULL DEFAULT 0,
  current_query_type varchar(160),
  current_query_message_id uuid,
  current_operation_id uuid,
  current_correlation_id uuid,
  attempts integer NOT NULL DEFAULT 0,
  last_error_code varchar(120),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  completed_at timestamptz,
  claim_token uuid,
  lease_until timestamptz,
  CONSTRAINT ck_recovery_projection_type CHECK (
    projection_type IN ('PERSON_STATUS', 'ACCOUNT_STATUS', 'ACADEMIC_ELIGIBILITY')
  ),
  CONSTRAINT ck_recovery_entity_key CHECK (
    (scope = 'FULL' AND entity_key IS NULL)
    OR (scope = 'ENTITY' AND entity_key IS NOT NULL AND btrim(entity_key) <> '')
  ),
  CONSTRAINT ck_recovery_attempts CHECK (query_attempt >= 0 AND attempts >= 0),
  CONSTRAINT ck_recovery_state_shape CHECK (
    (status = 'PENDING'
      AND current_query_type IS NULL
      AND current_query_message_id IS NULL
      AND current_operation_id IS NULL
      AND current_correlation_id IS NULL
      AND completed_at IS NULL)
    OR (status = 'WAITING_RESULT'
      AND current_query_type IS NOT NULL
      AND current_query_message_id IS NOT NULL
      AND current_operation_id IS NOT NULL
      AND current_correlation_id IS NOT NULL
      AND completed_at IS NULL
      AND claim_token IS NULL
      AND lease_until IS NULL)
    OR (status IN ('COMPLETED', 'FAILED')
      AND current_query_type IS NULL
      AND current_query_message_id IS NULL
      AND current_operation_id IS NULL
      AND current_correlation_id IS NULL
      AND completed_at IS NOT NULL
      AND claim_token IS NULL
      AND lease_until IS NULL)
  ),
  CONSTRAINT ck_recovery_claim CHECK (
    (claim_token IS NULL AND lease_until IS NULL)
    OR (claim_token IS NOT NULL AND lease_until IS NOT NULL)
  )
);

CREATE UNIQUE INDEX ux_recovery_full_active
  ON projection_recovery_jobs (projection_type)
  WHERE scope = 'FULL' AND status IN ('PENDING', 'WAITING_RESULT');
CREATE UNIQUE INDEX ux_recovery_entity_active
  ON projection_recovery_jobs (projection_type, entity_key)
  WHERE scope = 'ENTITY' AND status IN ('PENDING', 'WAITING_RESULT');
CREATE INDEX ix_recovery_waiting_result
  ON projection_recovery_jobs (current_query_message_id)
  WHERE status = 'WAITING_RESULT';
CREATE INDEX ix_recovery_claimable
  ON projection_recovery_jobs (status, updated_at)
  WHERE status = 'PENDING';

-- Stable V1 catalog seed. Catalogs are configuration, not runtime administration.
INSERT INTO systems (system_id, code, name, status, created_at, updated_at) VALUES
  ('00000000-0000-0000-0000-000000000101', 'LAVADO_ULTRASONICO', 'Lavado Ultrasonico', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000102', 'PRACTICAS_PROFESIONALES', 'Practicas Profesionales', 'ACTIVE', now(), now());

INSERT INTO roles (role_id, code, name, status, created_at, updated_at) VALUES
  ('00000000-0000-0000-0000-000000000201', 'ALUMNO', 'Alumno', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000202', 'SUPERVISOR_LAVADO', 'Supervisor Lavado', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000203', 'ADMINISTRADOR_LAVADO', 'Administrador Lavado', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000204', 'ADMINISTRADOR_PRACTICAS', 'Administrador Practicas', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000205', 'SUPERADMIN', 'Superadmin', 'ACTIVE', now(), now());

INSERT INTO permissions (permission_id, system_id, code, name, status, created_at, updated_at) VALUES
  ('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000101', 'wash.appointment.create', 'Create appointment', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000302', '00000000-0000-0000-0000-000000000101', 'wash.appointment.cancel', 'Cancel appointment', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000303', '00000000-0000-0000-0000-000000000101', 'wash.execution.exit.submit', 'Submit wash exit', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000304', '00000000-0000-0000-0000-000000000101', 'wash.execution.arrival.register', 'Register wash arrival', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000305', '00000000-0000-0000-0000-000000000101', 'wash.entry.decide', 'Decide wash entry', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000306', '00000000-0000-0000-0000-000000000101', 'wash.appointment.exceptional-authorization.grant', 'Grant exceptional appointment authorization', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000307', '00000000-0000-0000-0000-000000000101', 'wash.appointment.exceptional-authorization.cancel', 'Cancel exceptional appointment authorization', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000308', '00000000-0000-0000-0000-000000000101', 'wash.resource.disable', 'Disable resource', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000309', '00000000-0000-0000-0000-000000000101', 'wash.resource.restore', 'Restore resource', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000310', '00000000-0000-0000-0000-000000000101', 'wash.execution.reassignment.resolve', 'Resolve reassignment', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000311', '00000000-0000-0000-0000-000000000101', 'wash.execution.complete', 'Complete wash execution', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000312', '00000000-0000-0000-0000-000000000101', 'wash.execution.cancel-by-clinic', 'Cancel wash execution by clinic', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000313', '00000000-0000-0000-0000-000000000101', 'wash.admin.calendar.create', 'Create service calendar', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000314', '00000000-0000-0000-0000-000000000101', 'wash.admin.calendar.update', 'Update service calendar', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000315', '00000000-0000-0000-0000-000000000101', 'wash.admin.calendar.activate', 'Activate service calendar', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000316', '00000000-0000-0000-0000-000000000101', 'wash.admin.calendar.deactivate', 'Deactivate service calendar', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000317', '00000000-0000-0000-0000-000000000101', 'wash.admin.schedule.configure', 'Configure service schedule', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000318', '00000000-0000-0000-0000-000000000101', 'wash.admin.schedule.update', 'Update service schedule', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000319', '00000000-0000-0000-0000-000000000101', 'wash.admin.schedule.deactivate', 'Deactivate service schedule', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000320', '00000000-0000-0000-0000-000000000101', 'wash.admin.closure.schedule', 'Schedule administrative closure', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000321', '00000000-0000-0000-0000-000000000101', 'wash.admin.closure.cancel', 'Cancel administrative closure', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000322', '00000000-0000-0000-0000-000000000101', 'wash.admin.cabin.register', 'Register cabin', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000323', '00000000-0000-0000-0000-000000000101', 'wash.admin.cabin.update', 'Update cabin', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000324', '00000000-0000-0000-0000-000000000101', 'wash.admin.cabin.activate', 'Activate cabin', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000325', '00000000-0000-0000-0000-000000000101', 'wash.admin.cabin.deactivate', 'Deactivate cabin', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000326', '00000000-0000-0000-0000-000000000101', 'wash.admin.cabin.retire', 'Retire cabin', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000327', '00000000-0000-0000-0000-000000000101', 'wash.admin.tank.register', 'Register tank', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000328', '00000000-0000-0000-0000-000000000101', 'wash.admin.tank.update', 'Update tank', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000329', '00000000-0000-0000-0000-000000000101', 'wash.admin.tank.activate', 'Activate tank', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000330', '00000000-0000-0000-0000-000000000101', 'wash.admin.tank.deactivate', 'Deactivate tank', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000331', '00000000-0000-0000-0000-000000000101', 'wash.admin.tank.retire', 'Retire tank', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000332', '00000000-0000-0000-0000-000000000101', 'wash.appointment-slot.create', 'Create appointment slot', 'ACTIVE', now(), now()),
  ('00000000-0000-0000-0000-000000000333', '00000000-0000-0000-0000-000000000101', 'wash.appointment-slot.cancel', 'Cancel appointment slot', 'ACTIVE', now(), now());

INSERT INTO role_systems (role_id, system_id, assigned_at) VALUES
  ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000101', now()),
  ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000102', now()),
  ('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000101', now()),
  ('00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000101', now()),
  ('00000000-0000-0000-0000-000000000204', '00000000-0000-0000-0000-000000000102', now()),
  ('00000000-0000-0000-0000-000000000205', '00000000-0000-0000-0000-000000000101', now()),
  ('00000000-0000-0000-0000-000000000205', '00000000-0000-0000-0000-000000000102', now());

INSERT INTO role_permissions (role_id, permission_id, assigned_at)
SELECT '00000000-0000-0000-0000-000000000201', permission_id, now()
FROM permissions WHERE permission_id IN (
  '00000000-0000-0000-0000-000000000301',
  '00000000-0000-0000-0000-000000000302',
  '00000000-0000-0000-0000-000000000303'
);

INSERT INTO role_permissions (role_id, permission_id, assigned_at)
SELECT '00000000-0000-0000-0000-000000000202', permission_id, now()
FROM permissions WHERE permission_id BETWEEN
  '00000000-0000-0000-0000-000000000304'::uuid AND
  '00000000-0000-0000-0000-000000000312'::uuid;

INSERT INTO role_permissions (role_id, permission_id, assigned_at)
SELECT '00000000-0000-0000-0000-000000000203', permission_id, now()
FROM permissions WHERE permission_id BETWEEN
  '00000000-0000-0000-0000-000000000313'::uuid AND
  '00000000-0000-0000-0000-000000000333'::uuid;

GRANT USAGE ON SCHEMA public TO ${runtime_role};
GRANT USAGE ON TYPE catalog_status, login_type, projected_status, actor_type,
  system_access_status, inbox_status, idempotency_status, outbox_status,
  recovery_scope, recovery_job_status TO ${runtime_role};
GRANT SELECT ON systems, roles, permissions, role_systems, role_permissions TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE ON account_roles, system_accesses TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE ON account_status_projection, person_status_projection,
  academic_eligibility_projection, processed_messages, command_idempotency_records,
  outbox_messages, projection_recovery_jobs TO ${runtime_role};
GRANT SELECT, INSERT ON authorization_audit_entries TO ${runtime_role};

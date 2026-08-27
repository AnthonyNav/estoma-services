CREATE TYPE registro_estado AS ENUM (
  'PENDIENTE_VALIDACION',
  'CONFIRMADO',
  'RECHAZADO',
  'CANCELADO_ALUMNO',
  'CANCELADO_JORNADA_CANCELADA'
);
CREATE TYPE jornada_proyeccion_estado AS ENUM ('PUBLICADA', 'CANCELADA', 'FINALIZADA');
CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED');

-- Proyección local de solo lectura del catálogo de Jornadas, alimentada por
-- JornadaPublicada/Editada/Cancelada (jornadas-service). No es fuente
-- oficial: registros nunca hace join contra jornadas-db.
CREATE TABLE jornada_proyeccion (
  jornada_id uuid PRIMARY KEY,
  tipo_jornada_id uuid NOT NULL,
  fecha date NOT NULL,
  cupo_total integer NOT NULL CHECK (cupo_total >= 0),
  cupo_disponible integer NOT NULL CHECK (cupo_disponible >= 0),
  fecha_limite_documentos date NOT NULL,
  estado jornada_proyeccion_estado NOT NULL,
  source_version integer NOT NULL,
  updated_at timestamptz NOT NULL,
  CHECK (cupo_disponible <= cupo_total)
);

CREATE TABLE jornada_semestre_permitido_proyeccion (
  jornada_id uuid NOT NULL REFERENCES jornada_proyeccion,
  semestre integer NOT NULL,
  PRIMARY KEY (jornada_id, semestre)
);

CREATE TABLE registro (
  registro_id uuid PRIMARY KEY,
  jornada_id uuid NOT NULL REFERENCES jornada_proyeccion,
  alumno_id uuid NOT NULL,
  estado registro_estado NOT NULL DEFAULT 'PENDIENTE_VALIDACION',
  motivo_rechazo varchar(500),
  version integer NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CHECK (estado = 'RECHAZADO' OR motivo_rechazo IS NULL)
);

-- Invariante: un único Registro activo por Alumno a la vez
-- (data-model.md §4 de registros).
CREATE UNIQUE INDEX ux_registro_alumno_activo
ON registro (alumno_id)
WHERE estado IN ('PENDIENTE_VALIDACION', 'CONFIRMADO');

CREATE INDEX ix_registro_jornada
ON registro (jornada_id);

-- Transactional Outbox / Consumer Inbox / idempotencia de comandos durables —
-- mismo patrón transversal que jornadas-service / academic-service V1.
CREATE TABLE outbox_messages (
  message_id uuid PRIMARY KEY,
  message_type varchar(160) NOT NULL,
  message_version integer NOT NULL,
  message_kind varchar(20) NOT NULL,
  aggregate_id uuid,
  aggregate_version integer,
  correlation_id uuid NOT NULL,
  causation_id uuid,
  payload jsonb NOT NULL,
  metadata jsonb,
  status outbox_status NOT NULL DEFAULT 'PENDING',
  attempts integer NOT NULL DEFAULT 0,
  claim_token uuid,
  lease_until timestamptz,
  occurred_at timestamptz NOT NULL,
  available_at timestamptz NOT NULL,
  published_at timestamptz,
  last_error_code varchar(80),
  last_error_at timestamptz,
  CHECK (
    (status = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (status <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL)
  )
);

CREATE INDEX ix_registros_outbox_pending
ON outbox_messages (available_at, occurred_at)
WHERE status = 'PENDING';

CREATE INDEX ix_registros_outbox_processing_lease
ON outbox_messages (lease_until)
WHERE status = 'PROCESSING';

CREATE TABLE processed_messages (
  consumer_name varchar(120) NOT NULL,
  message_id uuid NOT NULL,
  correlation_id uuid,
  message_type varchar(160),
  message_kind varchar(20),
  producer varchar(80),
  aggregate_id uuid,
  aggregate_version integer,
  envelope jsonb,
  processing_state varchar(30) NOT NULL DEFAULT 'APPLIED',
  reconciliation_state varchar(40),
  processed_at timestamptz NOT NULL,
  CHECK (processing_state IN ('DEFERRED', 'APPLIED')),
  CHECK (
    (processing_state = 'DEFERRED' AND reconciliation_state IN ('EVENT_PENDING', 'PROJECTION_RECONCILIATION'))
    OR (processing_state = 'APPLIED' AND reconciliation_state IS NULL)
  ),
  PRIMARY KEY (consumer_name, message_id)
);

CREATE TABLE idempotency_records (
  producer varchar(80) NOT NULL,
  idempotency_key varchar(200) NOT NULL,
  message_type varchar(160) NOT NULL,
  request_fingerprint varchar(128) NOT NULL,
  aggregate_id uuid,
  aggregate_version integer,
  result_status varchar(20) NOT NULL,
  error_code varchar(80),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (producer, idempotency_key)
);

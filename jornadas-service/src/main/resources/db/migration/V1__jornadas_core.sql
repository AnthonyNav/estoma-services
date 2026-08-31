CREATE TYPE jornada_estado AS ENUM ('PUBLICADA', 'CANCELADA', 'FINALIZADA');
CREATE TYPE outbox_status AS ENUM ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED');

CREATE TABLE tipo_jornada (
  tipo_jornada_id uuid PRIMARY KEY,
  nombre varchar(120) NOT NULL UNIQUE,
  activo boolean NOT NULL DEFAULT true,
  version integer NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);

-- Rango de sanidad técnica, no regla de negocio: qué semestres corresponden a
-- cada tipo de jornada sigue siendo una pregunta abierta con la Facultad
-- (PRD, Q02). La configuración real es 100% dirigida por datos en esta tabla.
CREATE TABLE tipo_jornada_semestre_permitido (
  tipo_jornada_id uuid NOT NULL REFERENCES tipo_jornada,
  semestre integer NOT NULL CHECK (semestre BETWEEN 1 AND 14),
  PRIMARY KEY (tipo_jornada_id, semestre)
);

CREATE TABLE jornada (
  jornada_id uuid PRIMARY KEY,
  tipo_jornada_id uuid NOT NULL REFERENCES tipo_jornada,
  nombre varchar(160) NOT NULL,
  fecha date NOT NULL,
  hora_inicio time NOT NULL,
  hora_fin time NOT NULL,
  lugar varchar(200) NOT NULL,
  cupo_total integer NOT NULL CHECK (cupo_total >= 0),
  fecha_limite_documentos date NOT NULL,
  descripcion text,
  estado jornada_estado NOT NULL DEFAULT 'PUBLICADA',
  motivo_cancelacion varchar(500),
  version integer NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CHECK (hora_fin > hora_inicio),
  CHECK (estado = 'CANCELADA' OR motivo_cancelacion IS NULL)
);

-- Usado por el worker programado de finalización automática por fecha
-- (events.md §4 / commands.md §4 de jornadas): selecciona candidatas a
-- FINALIZADA sin escanear toda la tabla.
CREATE INDEX ix_jornada_publicada_por_fecha
ON jornada (fecha)
WHERE estado = 'PUBLICADA';

-- Proyección local de solo lectura, alimentada por eventos de
-- cumplimiento-documental. No es fuente oficial y puede reconstruirse.
CREATE TABLE alumno_documentacion_general_proyeccion (
  alumno_id uuid PRIMARY KEY,
  estado_vigencia varchar(20) NOT NULL,
  source_version integer NOT NULL,
  updated_at timestamptz NOT NULL,
  CHECK (estado_vigencia IN ('VIGENTE', 'VENCIDA', 'RECHAZADA', 'PENDIENTE'))
);

-- Proyección local de solo lectura, alimentada por StudentSemesterChanged
-- (academic-service). No es fuente oficial y puede reconstruirse.
CREATE TABLE alumno_semestre_proyeccion (
  alumno_id uuid PRIMARY KEY,
  semestre integer NOT NULL,
  source_version integer NOT NULL,
  updated_at timestamptz NOT NULL
);

-- Transactional Outbox / Consumer Inbox / idempotencia de comandos durables —
-- mismo patrón transversal que academic-service V1
-- (academic-service/src/main/resources/db/migration/V1__academic_core.sql).
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

CREATE INDEX ix_jornadas_outbox_pending
ON outbox_messages (available_at, occurred_at)
WHERE status = 'PENDING';

CREATE INDEX ix_jornadas_outbox_processing_lease
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

CREATE INDEX ix_jornada_tipo_jornada
ON jornada (tipo_jornada_id);

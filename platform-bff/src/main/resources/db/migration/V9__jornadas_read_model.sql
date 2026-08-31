-- Prácticas Profesionales — Jornadas catalog, projected read-only from jornadas-service's
-- JornadaPublicada/JornadaCancelada Events. Public read surface: no per-account scoping.

CREATE TABLE jornada_read_model (
    jornada_id UUID PRIMARY KEY, tipo_jornada_id UUID, nombre VARCHAR(160),
    fecha DATE, hora_inicio TIME, hora_fin TIME, lugar VARCHAR(200),
    cupo_total INTEGER, fecha_limite_documentos DATE, descripcion TEXT,
    estado VARCHAR(32) NOT NULL, source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX jornada_read_model_estado_fecha_idx ON jornada_read_model(estado, fecha);

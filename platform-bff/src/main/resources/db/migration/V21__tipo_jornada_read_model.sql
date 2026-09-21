-- Prácticas Profesionales — Tipos de Jornada catalog, projected read-only from
-- jornadas-service's TipoJornadaConfigurado Event. Public read surface, same pattern
-- as jornada_read_model (V9) — semestres_permitidos stored as a jsonb integer array
-- since this port has no existing native-array column precedent.

CREATE TABLE tipo_jornada_read_model (
    tipo_jornada_id UUID PRIMARY KEY,
    nombre VARCHAR(120),
    semestres_permitidos JSONB,
    source_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

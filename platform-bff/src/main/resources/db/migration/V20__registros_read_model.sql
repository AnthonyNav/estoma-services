-- Prácticas Profesionales — Registros, projected read-only from registros-service's
-- RegistroCreado/RegistroConfirmado/RegistroRechazado/RegistroCanceladoPorAlumno/
-- RegistroCanceladoPorJornadaCancelada/RegistroCompletado Events. Scoped per Alumno and per
-- Jornada — same pattern as jornada_read_model (V9).

CREATE TABLE registro_read_model (
    registro_id UUID PRIMARY KEY, jornada_id UUID, alumno_account_id UUID,
    estado VARCHAR(32) NOT NULL, motivo_rechazo VARCHAR(500),
    source_version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX registro_read_model_alumno_idx ON registro_read_model(alumno_account_id, updated_at);
CREATE INDEX registro_read_model_jornada_idx ON registro_read_model(jornada_id);

-- JornadaCancelada already carries `motivo` — the read model just never captured it.
ALTER TABLE jornada_read_model ADD COLUMN motivo_cancelacion VARCHAR(500);

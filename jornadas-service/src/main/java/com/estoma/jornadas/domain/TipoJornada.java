package com.estoma.jornadas.domain;

import java.util.List;
import java.util.UUID;

public record TipoJornada(UUID tipoJornadaId, String nombre, boolean activo, int version, List<Integer> semestresPermitidos) {}

package com.estoma.platformbff.contracts.http.jornadas;

import java.util.List;
import java.util.UUID;

public record TipoJornadaResponse(UUID tipoJornadaId, String nombre, List<Integer> semestresPermitidos) {}

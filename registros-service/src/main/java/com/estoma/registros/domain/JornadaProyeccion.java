package com.estoma.registros.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Local, read-only projection of jornadas-service's Jornada, fed by
 * JornadaPublicada/Editada/Cancelada. Never the source of truth for cupo —
 * registros is the only owner of cupo_disponible in real time.
 */
public record JornadaProyeccion(
    UUID jornadaId,
    UUID tipoJornadaId,
    LocalDate fecha,
    int cupoTotal,
    int cupoDisponible,
    LocalDate fechaLimiteDocumentos,
    JornadaProyeccionEstado estado,
    int sourceVersion,
    List<Integer> semestresPermitidos) {}

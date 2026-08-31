package com.estoma.jornadas.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record Jornada(
    UUID jornadaId,
    UUID tipoJornadaId,
    String nombre,
    LocalDate fecha,
    LocalTime horaInicio,
    LocalTime horaFin,
    String lugar,
    int cupoTotal,
    LocalDate fechaLimiteDocumentos,
    String descripcion,
    JornadaEstado estado,
    String motivoCancelacion,
    int version) {}

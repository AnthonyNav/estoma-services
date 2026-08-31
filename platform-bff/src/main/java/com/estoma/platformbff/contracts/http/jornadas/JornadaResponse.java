package com.estoma.platformbff.contracts.http.jornadas;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record JornadaResponse(
    UUID jornadaId,
    UUID tipoJornadaId,
    String nombre,
    LocalDate fecha,
    LocalTime horaInicio,
    LocalTime horaFin,
    String lugar,
    Integer cupoTotal,
    LocalDate fechaLimiteDocumentos,
    String descripcion,
    String estado) {}

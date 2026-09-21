package com.estoma.platformbff.contracts.http.jornadas;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Client-oriented request DTOs for Jornadas writes. Actor identity is server-derived. */
public final class JornadaHttpRequests {
  private JornadaHttpRequests() {}

  public record PublicarJornadaRequest(
      @NotNull UUID tipoJornadaId,
      @NotBlank @Size(max = 160) String nombre,
      @NotNull LocalDate fecha,
      @NotNull LocalTime horaInicio,
      @NotNull LocalTime horaFin,
      @NotBlank @Size(max = 200) String lugar,
      @PositiveOrZero int cupoTotal,
      @NotNull LocalDate fechaLimiteDocumentos,
      @Size(max = 2000) String descripcion) {}

  public record EditarJornadaRequest(
      @Positive long expectedVersion,
      @Size(max = 160) String nombre,
      LocalDate fecha,
      LocalTime horaInicio,
      LocalTime horaFin,
      @Size(max = 200) String lugar,
      @PositiveOrZero Integer cupoTotal,
      LocalDate fechaLimiteDocumentos,
      @Size(max = 2000) String descripcion) {}

  public record CancelarJornadaRequest(
      @NotBlank @Size(max = 500) String motivo) {}

  public record ConfigurarTipoJornadaRequest(
      UUID tipoJornadaId,
      @NotBlank @Size(max = 120) String nombre,
      @NotEmpty List<Integer> semestresPermitidos) {}
}

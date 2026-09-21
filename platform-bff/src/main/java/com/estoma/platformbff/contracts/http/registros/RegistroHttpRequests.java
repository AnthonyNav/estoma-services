package com.estoma.platformbff.contracts.http.registros;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Client-oriented request DTOs for Registros writes. Actor identity is server-derived. */
public final class RegistroHttpRequests {
  private RegistroHttpRequests() {}

  public record CrearRegistroRequest(@NotNull UUID jornadaId) {}

  public record RechazarRegistroRequest(@NotBlank @Size(max = 500) String motivo) {}
}

package com.estoma.platformbff.contracts.http.registros;

import java.util.UUID;

public record RegistroResponse(
    UUID registroId,
    UUID jornadaId,
    UUID alumnoAccountId,
    String estado,
    String motivoRechazo) {}

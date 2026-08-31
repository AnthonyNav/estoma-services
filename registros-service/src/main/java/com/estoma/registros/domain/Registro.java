package com.estoma.registros.domain;

import java.util.UUID;

public record Registro(
    UUID registroId,
    UUID jornadaId,
    UUID alumnoId,
    RegistroEstado estado,
    String motivoRechazo,
    int version) {}

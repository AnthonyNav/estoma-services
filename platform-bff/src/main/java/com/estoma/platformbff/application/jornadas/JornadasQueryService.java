package com.estoma.platformbff.application.jornadas;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.contracts.http.jornadas.JornadaResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class JornadasQueryService {
  private final ProjectionQueryPort projections;

  public JornadasQueryService(ProjectionQueryPort projections) {
    this.projections = projections;
  }

  public List<JornadaResponse> listJornadas() {
    return projections.findJornadas().stream()
        .map(j -> new JornadaResponse(j.jornadaId(), j.tipoJornadaId(), j.nombre(), j.fecha(),
            j.horaInicio(), j.horaFin(), j.lugar(), j.cupoTotal(), j.fechaLimiteDocumentos(),
            j.descripcion(), j.estado()))
        .toList();
  }
}

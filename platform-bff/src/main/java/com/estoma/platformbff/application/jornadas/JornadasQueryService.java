package com.estoma.platformbff.application.jornadas;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.contracts.http.jornadas.JornadaResponse;
import com.estoma.platformbff.contracts.http.jornadas.TipoJornadaResponse;
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

  public List<TipoJornadaResponse> listTipos() {
    return projections.findTiposJornada().stream()
        .map(t -> new TipoJornadaResponse(t.tipoJornadaId(), t.nombre(), t.semestresPermitidos()))
        .toList();
  }
}

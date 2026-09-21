package com.estoma.platformbff.application.registros;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.contracts.http.registros.RegistroResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RegistrosQueryService {
  private final ProjectionQueryPort projections;

  public RegistrosQueryService(ProjectionQueryPort projections) {
    this.projections = projections;
  }

  public List<RegistroResponse> misRegistros(UUID alumnoAccountId) {
    return projections.findRegistrosByAlumno(alumnoAccountId).stream().map(this::toResponse).toList();
  }

  public List<RegistroResponse> registrosDeJornada(UUID jornadaId) {
    return projections.findRegistrosByJornada(jornadaId).stream().map(this::toResponse).toList();
  }

  /** Never client-supplied — resolved server-side from the academic-service projection. */
  public Optional<Integer> currentSemester(UUID alumnoAccountId) {
    return projections.findCurrentSemester(alumnoAccountId);
  }

  private RegistroResponse toResponse(com.estoma.platformbff.application.projection.ProjectionReadModel.Registro r) {
    return new RegistroResponse(r.registroId(), r.jornadaId(), r.alumnoAccountId(), r.estado(), r.motivoRechazo());
  }
}

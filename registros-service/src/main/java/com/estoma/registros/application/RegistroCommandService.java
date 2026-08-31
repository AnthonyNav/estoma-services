package com.estoma.registros.application;

import com.estoma.registros.domain.JornadaProyeccion;
import com.estoma.registros.domain.JornadaProyeccionEstado;
import com.estoma.registros.domain.Registro;
import com.estoma.registros.infrastructure.persistence.JdbcJornadaProyeccionRepository;
import com.estoma.registros.infrastructure.persistence.JdbcRegistroRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V1 simplification (Saturday demo scope): no lista de espera — CrearRegistro
 * is rejected outright when there is no cupo, instead of queueing. No
 * validation against cumplimiento-documental (service does not exist yet);
 * the caller supplies semestreAlumno directly instead of resolving it from a
 * local academic-service projection. See registros-service/README.md.
 */
@Service
public class RegistroCommandService {

  private final JdbcRegistroRepository registroRepository;
  private final JdbcJornadaProyeccionRepository jornadaProyeccionRepository;

  public RegistroCommandService(
      JdbcRegistroRepository registroRepository, JdbcJornadaProyeccionRepository jornadaProyeccionRepository) {
    this.registroRepository = registroRepository;
    this.jornadaProyeccionRepository = jornadaProyeccionRepository;
  }

  @Transactional
  public Registro crearRegistro(UUID jornadaId, UUID alumnoId, int semestreAlumno) {
    JornadaProyeccion jornada =
        jornadaProyeccionRepository
            .findById(jornadaId)
            .orElseThrow(() -> reject("REGISTROS.JORNADA_NO_ENCONTRADA", "jornadaId no existe: " + jornadaId));

    if (jornada.estado() != JornadaProyeccionEstado.PUBLICADA) {
      throw reject("REGISTROS.JORNADA_NO_DISPONIBLE", "la Jornada no está Publicada: " + jornada.estado());
    }
    if (!jornada.semestresPermitidos().contains(semestreAlumno)) {
      throw reject(
          "REGISTROS.SEMESTRE_NO_PERMITIDO",
          "semestre " + semestreAlumno + " no permitido para esta Jornada");
    }
    if (registroRepository.existeActivoParaAlumno(alumnoId)) {
      throw reject(
          "REGISTROS.REGISTRO_ACTIVO_EXISTENTE",
          "el Alumno ya tiene un Registro activo en otra Jornada");
    }
    if (!jornadaProyeccionRepository.reservarCupo(jornadaId)) {
      throw reject("REGISTROS.SIN_CUPO_DISPONIBLE", "no hay cupo disponible en esta Jornada");
    }

    return registroRepository.insert(jornadaId, alumnoId);
  }

  @Transactional
  public Registro confirmarRegistro(UUID registroId) {
    return registroRepository
        .confirmarSiPendiente(registroId)
        .orElseGet(
            () -> {
              Registro actual = requireRegistro(registroId);
              throw reject(
                  "REGISTROS.REGISTRO_NO_PENDIENTE", "el Registro no está Pendiente de validación: " + actual.estado());
            });
  }

  @Transactional
  public Registro rechazarRegistro(UUID registroId, String motivo) {
    if (motivo == null || motivo.isBlank()) {
      throw reject("REGISTROS.MOTIVO_REQUERIDO", "motivo de rechazo es obligatorio");
    }
    Registro rechazado =
        registroRepository
            .rechazarSiPendiente(registroId, motivo)
            .orElseGet(
                () -> {
                  Registro actual = requireRegistro(registroId);
                  throw reject(
                      "REGISTROS.REGISTRO_NO_PENDIENTE",
                      "el Registro no está Pendiente de validación: " + actual.estado());
                });
    jornadaProyeccionRepository.liberarCupo(rechazado.jornadaId());
    return rechazado;
  }

  @Transactional
  public Registro cancelarRegistroPorAlumno(UUID registroId, UUID alumnoId) {
    Registro actual = requireRegistro(registroId);
    if (!actual.alumnoId().equals(alumnoId)) {
      throw reject("REGISTROS.NO_AUTORIZADO", "el Registro no pertenece a este Alumno");
    }

    JornadaProyeccion jornada =
        jornadaProyeccionRepository
            .findById(actual.jornadaId())
            .orElseThrow(() -> reject("REGISTROS.JORNADA_NO_ENCONTRADA", "jornadaId no existe: " + actual.jornadaId()));
    if (jornada.fecha().isBefore(LocalDate.now().plusDays(3))) {
      throw reject(
          "REGISTROS.FUERA_DE_PLAZO_CANCELACION", "faltan menos de 3 días para la Jornada, no se puede cancelar");
    }

    Registro cancelado =
        registroRepository
            .cancelarPorAlumnoSiPendiente(registroId)
            .orElseGet(
                () -> {
                  throw reject(
                      "REGISTROS.REGISTRO_NO_CANCELABLE",
                      "el Registro ya está Confirmado, Cancelado o Rechazado: " + actual.estado());
                });
    jornadaProyeccionRepository.liberarCupo(cancelado.jornadaId());
    return cancelado;
  }

  private Registro requireRegistro(UUID registroId) {
    return registroRepository
        .findById(registroId)
        .orElseThrow(() -> reject("REGISTROS.REGISTRO_NO_ENCONTRADO", "registroId no existe: " + registroId));
  }

  private static RegistroRejectedException reject(String code, String message) {
    return new RegistroRejectedException(code, message);
  }
}

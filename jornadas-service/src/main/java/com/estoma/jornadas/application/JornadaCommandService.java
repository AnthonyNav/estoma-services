package com.estoma.jornadas.application;

import com.estoma.jornadas.domain.Jornada;
import com.estoma.jornadas.infrastructure.persistence.JdbcJornadaRepository;
import com.estoma.jornadas.infrastructure.persistence.JdbcTipoJornadaRepository;
import com.estoma.jornadas.infrastructure.persistence.JdbcTipoJornadaRepository.TipoJornadaSaved;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the Jornadas catalog commands. V1 simplification: no
 * Transactional Outbox yet — the caller (JornadaCommandDispatcher) publishes
 * the Result/Event right after this returns, in a separate, non-atomic step
 * (see jornadas-service/README.md).
 */
@Service
public class JornadaCommandService {

  private final JdbcJornadaRepository jornadaRepository;
  private final JdbcTipoJornadaRepository tipoJornadaRepository;

  public JornadaCommandService(
      JdbcJornadaRepository jornadaRepository, JdbcTipoJornadaRepository tipoJornadaRepository) {
    this.jornadaRepository = jornadaRepository;
    this.tipoJornadaRepository = tipoJornadaRepository;
  }

  @Transactional
  public Jornada publicarJornada(
      UUID tipoJornadaId,
      String nombre,
      LocalDate fecha,
      LocalTime horaInicio,
      LocalTime horaFin,
      String lugar,
      int cupoTotal,
      LocalDate fechaLimiteDocumentos,
      String descripcion) {
    if (!tipoJornadaRepository.existsActivo(tipoJornadaId)) {
      throw reject("JORNADAS.TIPO_JORNADA_NO_ENCONTRADO", "tipoJornadaId no existe o no está activo: " + tipoJornadaId);
    }
    validarDatos(cupoTotal, horaInicio, horaFin);
    return jornadaRepository.insert(
        tipoJornadaId, nombre, fecha, horaInicio, horaFin, lugar, cupoTotal, fechaLimiteDocumentos, descripcion);
  }

  @Transactional
  public Jornada editarJornada(UUID jornadaId, int expectedVersion, EditarJornadaFields fields) {
    Jornada actual = requireJornada(jornadaId);
    if (actual.estado() != com.estoma.jornadas.domain.JornadaEstado.PUBLICADA) {
      throw reject("JORNADAS.JORNADA_NO_EDITABLE", "la Jornada no está Publicada: " + actual.estado());
    }

    String nombre = fields.nombre() != null ? fields.nombre() : actual.nombre();
    LocalDate fecha = fields.fecha() != null ? fields.fecha() : actual.fecha();
    LocalTime horaInicio = fields.horaInicio() != null ? fields.horaInicio() : actual.horaInicio();
    LocalTime horaFin = fields.horaFin() != null ? fields.horaFin() : actual.horaFin();
    String lugar = fields.lugar() != null ? fields.lugar() : actual.lugar();
    int cupoTotal = fields.cupoTotal() != null ? fields.cupoTotal() : actual.cupoTotal();
    LocalDate fechaLimiteDocumentos =
        fields.fechaLimiteDocumentos() != null ? fields.fechaLimiteDocumentos() : actual.fechaLimiteDocumentos();
    String descripcion = fields.descripcion() != null ? fields.descripcion() : actual.descripcion();

    validarDatos(cupoTotal, horaInicio, horaFin);

    return jornadaRepository
        .updateIfPublicadaAndVersion(
            jornadaId, expectedVersion, nombre, fecha, horaInicio, horaFin, lugar, cupoTotal,
            fechaLimiteDocumentos, descripcion)
        .orElseGet(
            () -> {
              Jornada latest = requireJornada(jornadaId);
              if (latest.version() != expectedVersion) {
                throw reject(
                    "JORNADAS.CONFLICTO_VERSION",
                    "aggregateVersion desalineado: esperado " + expectedVersion + ", actual " + latest.version());
              }
              throw reject("JORNADAS.JORNADA_NO_EDITABLE", "la Jornada dejó de estar Publicada: " + latest.estado());
            });
  }

  @Transactional
  public Jornada cancelarJornada(UUID jornadaId, String motivo) {
    if (motivo == null || motivo.isBlank()) {
      throw reject("JORNADAS.MOTIVO_REQUERIDO", "motivo de cancelación es obligatorio");
    }
    return jornadaRepository
        .cancelIfPublicada(jornadaId, motivo)
        .orElseGet(
            () -> {
              Jornada latest = requireJornada(jornadaId);
              throw reject("JORNADAS.JORNADA_NO_CANCELABLE", "la Jornada no está Publicada: " + latest.estado());
            });
  }

  @Transactional
  public TipoJornadaSaved configurarTipoJornada(UUID tipoJornadaId, String nombre, List<Integer> semestresPermitidos) {
    if (nombre == null || nombre.isBlank()) {
      throw reject("JORNADAS.DATOS_INVALIDOS", "nombre es obligatorio");
    }
    if (semestresPermitidos == null || semestresPermitidos.isEmpty()) {
      throw reject("JORNADAS.DATOS_INVALIDOS", "semestresPermitidos no puede estar vacío");
    }
    for (Integer semestre : semestresPermitidos) {
      if (semestre == null || semestre < 1 || semestre > 14) {
        throw reject("JORNADAS.DATOS_INVALIDOS", "semestre fuera de rango (1-14): " + semestre);
      }
    }
    if (tipoJornadaId != null && tipoJornadaRepository.findById(tipoJornadaId).isEmpty()) {
      throw reject("JORNADAS.TIPO_JORNADA_NO_ENCONTRADO", "tipoJornadaId no existe: " + tipoJornadaId);
    }
    return tipoJornadaRepository.upsert(tipoJornadaId, nombre, semestresPermitidos);
  }

  public List<Integer> semestresPermitidos(UUID tipoJornadaId) {
    return tipoJornadaRepository.semestresPermitidos(tipoJornadaId);
  }

  private Jornada requireJornada(UUID jornadaId) {
    return jornadaRepository
        .findById(jornadaId)
        .orElseThrow(() -> reject("JORNADAS.JORNADA_NO_ENCONTRADA", "jornadaId no existe: " + jornadaId));
  }

  private static void validarDatos(int cupoTotal, LocalTime horaInicio, LocalTime horaFin) {
    if (cupoTotal < 0) {
      throw reject("JORNADAS.CUPO_INVALIDO", "cupoTotal no puede ser negativo: " + cupoTotal);
    }
    if (!horaFin.isAfter(horaInicio)) {
      throw reject("JORNADAS.HORARIO_INVALIDO", "horaFin debe ser posterior a horaInicio");
    }
  }

  private static JornadaRejectedException reject(String code, String message) {
    return new JornadaRejectedException(code, message);
  }
}

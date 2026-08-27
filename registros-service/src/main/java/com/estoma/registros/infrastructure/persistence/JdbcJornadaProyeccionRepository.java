package com.estoma.registros.infrastructure.persistence;

import com.estoma.registros.domain.JornadaProyeccion;
import com.estoma.registros.domain.JornadaProyeccionEstado;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcJornadaProyeccionRepository {

  private final JdbcTemplate jdbc;

  public JdbcJornadaProyeccionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void upsertFromPublicada(
      UUID jornadaId,
      UUID tipoJornadaId,
      LocalDate fecha,
      int cupoTotal,
      LocalDate fechaLimiteDocumentos,
      List<Integer> semestresPermitidos,
      int sourceVersion) {
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO jornada_proyeccion (jornada_id, tipo_jornada_id, fecha, cupo_total, cupo_disponible, "
            + "fecha_limite_documentos, estado, source_version, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'PUBLICADA', ?, ?) "
            + "ON CONFLICT (jornada_id) DO NOTHING",
        jornadaId, tipoJornadaId, fecha, cupoTotal, cupoTotal, fechaLimiteDocumentos, sourceVersion, now);

    if (semestresPermitidos != null && !semestresPermitidos.isEmpty()) {
      jdbc.batchUpdate(
          "INSERT INTO jornada_semestre_permitido_proyeccion (jornada_id, semestre) VALUES (?, ?) "
              + "ON CONFLICT DO NOTHING",
          semestresPermitidos,
          semestresPermitidos.size(),
          (ps, semestre) -> {
            ps.setObject(1, jornadaId);
            ps.setInt(2, semestre);
          });
    }
  }

  /** Applies only the editable fields present; the caller resolves which ones changed. */
  @Transactional
  public void applyEditada(
      UUID jornadaId, LocalDate fecha, Integer cupoTotal, LocalDate fechaLimiteDocumentos, int sourceVersion) {
    Optional<JornadaProyeccion> current = findById(jornadaId);
    if (current.isEmpty()) {
      return;
    }
    JornadaProyeccion actual = current.get();
    LocalDate nuevaFecha = fecha != null ? fecha : actual.fecha();
    LocalDate nuevaFechaLimite = fechaLimiteDocumentos != null ? fechaLimiteDocumentos : actual.fechaLimiteDocumentos();
    int nuevoCupoTotal = cupoTotal != null ? cupoTotal : actual.cupoTotal();
    int deltaCupo = nuevoCupoTotal - actual.cupoTotal();
    int nuevoCupoDisponible = Math.max(0, actual.cupoDisponible() + deltaCupo);

    jdbc.update(
        "UPDATE jornada_proyeccion SET fecha = ?, cupo_total = ?, cupo_disponible = ?, "
            + "fecha_limite_documentos = ?, source_version = ?, updated_at = ? WHERE jornada_id = ?",
        nuevaFecha, nuevoCupoTotal, nuevoCupoDisponible, nuevaFechaLimite, sourceVersion,
        Timestamp.from(Instant.now()), jornadaId);
  }

  @Transactional
  public void marcarCancelada(UUID jornadaId, int sourceVersion) {
    jdbc.update(
        "UPDATE jornada_proyeccion SET estado = 'CANCELADA', source_version = ?, updated_at = ? WHERE jornada_id = ?",
        sourceVersion, Timestamp.from(Instant.now()), jornadaId);
  }

  /** Atomic check-and-decrement: returns true only if a cupo was actually available and reserved. */
  public boolean reservarCupo(UUID jornadaId) {
    int updated =
        jdbc.update(
            "UPDATE jornada_proyeccion SET cupo_disponible = cupo_disponible - 1, updated_at = ? "
                + "WHERE jornada_id = ? AND cupo_disponible > 0 AND estado = 'PUBLICADA'",
            Timestamp.from(Instant.now()), jornadaId);
    return updated > 0;
  }

  public void liberarCupo(UUID jornadaId) {
    jdbc.update(
        "UPDATE jornada_proyeccion SET cupo_disponible = LEAST(cupo_total, cupo_disponible + 1), updated_at = ? "
            + "WHERE jornada_id = ?",
        Timestamp.from(Instant.now()), jornadaId);
  }

  public List<Integer> semestresPermitidos(UUID jornadaId) {
    return jdbc.queryForList(
        "SELECT semestre FROM jornada_semestre_permitido_proyeccion WHERE jornada_id = ? ORDER BY semestre",
        Integer.class,
        jornadaId);
  }

  public Optional<JornadaProyeccion> findById(UUID jornadaId) {
    List<JornadaProyeccion> rows =
        jdbc.query(
            "SELECT jornada_id, tipo_jornada_id, fecha, cupo_total, cupo_disponible, fecha_limite_documentos, "
                + "estado, source_version FROM jornada_proyeccion WHERE jornada_id = ?",
            this::mapRow,
            jornadaId);
    return rows.stream().findFirst();
  }

  public List<UUID> jornadaIdsActivas(UUID jornadaId) {
    return jdbc.queryForList(
        "SELECT jornada_id FROM jornada_proyeccion WHERE jornada_id = ? AND estado = 'PUBLICADA'",
        UUID.class,
        jornadaId);
  }

  private JornadaProyeccion mapRow(ResultSet rs, int rowNum) throws SQLException {
    UUID jornadaId = (UUID) rs.getObject("jornada_id");
    return new JornadaProyeccion(
        jornadaId,
        (UUID) rs.getObject("tipo_jornada_id"),
        rs.getObject("fecha", LocalDate.class),
        rs.getInt("cupo_total"),
        rs.getInt("cupo_disponible"),
        rs.getObject("fecha_limite_documentos", LocalDate.class),
        JornadaProyeccionEstado.valueOf(rs.getString("estado")),
        rs.getInt("source_version"),
        semestresPermitidos(jornadaId));
  }
}

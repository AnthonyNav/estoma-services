package com.estoma.jornadas.infrastructure.persistence;

import com.estoma.jornadas.domain.Jornada;
import com.estoma.jornadas.domain.JornadaEstado;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJornadaRepository {

  private static final RowMapper<Jornada> ROW_MAPPER = JdbcJornadaRepository::mapRow;

  private final JdbcTemplate jdbc;

  public JdbcJornadaRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Jornada insert(
      UUID tipoJornadaId,
      String nombre,
      LocalDate fecha,
      LocalTime horaInicio,
      LocalTime horaFin,
      String lugar,
      int cupoTotal,
      LocalDate fechaLimiteDocumentos,
      String descripcion) {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO jornada (jornada_id, tipo_jornada_id, nombre, fecha, hora_inicio, hora_fin, lugar, "
            + "cupo_total, fecha_limite_documentos, descripcion, estado, version, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PUBLICADA', 1, ?, ?)",
        id, tipoJornadaId, nombre, fecha, horaInicio, horaFin, lugar, cupoTotal, fechaLimiteDocumentos,
        descripcion, now, now);
    return findById(id).orElseThrow();
  }

  public Optional<Jornada> findById(UUID jornadaId) {
    List<Jornada> rows =
        jdbc.query(
            "SELECT jornada_id, tipo_jornada_id, nombre, fecha, hora_inicio, hora_fin, lugar, cupo_total, "
                + "fecha_limite_documentos, descripcion, estado, motivo_cancelacion, version "
                + "FROM jornada WHERE jornada_id = ?",
            ROW_MAPPER,
            jornadaId);
    return rows.stream().findFirst();
  }

  /** Full-row replace of the editable fields; caller resolves partial-update merging before calling this. */
  public Optional<Jornada> updateIfPublicadaAndVersion(
      UUID jornadaId,
      int expectedVersion,
      String nombre,
      LocalDate fecha,
      LocalTime horaInicio,
      LocalTime horaFin,
      String lugar,
      int cupoTotal,
      LocalDate fechaLimiteDocumentos,
      String descripcion) {
    int updated =
        jdbc.update(
            "UPDATE jornada SET nombre = ?, fecha = ?, hora_inicio = ?, hora_fin = ?, lugar = ?, "
                + "cupo_total = ?, fecha_limite_documentos = ?, descripcion = ?, version = version + 1, "
                + "updated_at = ? "
                + "WHERE jornada_id = ? AND version = ? AND estado = 'PUBLICADA'",
            nombre, fecha, horaInicio, horaFin, lugar, cupoTotal, fechaLimiteDocumentos, descripcion,
            Timestamp.from(Instant.now()), jornadaId, expectedVersion);
    return updated == 0 ? Optional.empty() : findById(jornadaId);
  }

  public Optional<Jornada> cancelIfPublicada(UUID jornadaId, String motivo) {
    int updated =
        jdbc.update(
            "UPDATE jornada SET estado = 'CANCELADA', motivo_cancelacion = ?, version = version + 1, "
                + "updated_at = ? WHERE jornada_id = ? AND estado = 'PUBLICADA'",
            motivo, Timestamp.from(Instant.now()), jornadaId);
    return updated == 0 ? Optional.empty() : findById(jornadaId);
  }

  /** Finalizes every Jornada whose fecha already passed. Idempotent: the estado='PUBLICADA' filter is the guard. */
  public List<Jornada> finalizarVencidas() {
    return jdbc.query(
        "UPDATE jornada SET estado = 'FINALIZADA', version = version + 1, updated_at = ? "
            + "WHERE estado = 'PUBLICADA' AND fecha < CURRENT_DATE "
            + "RETURNING jornada_id, tipo_jornada_id, nombre, fecha, hora_inicio, hora_fin, lugar, cupo_total, "
            + "fecha_limite_documentos, descripcion, estado, motivo_cancelacion, version",
        ROW_MAPPER,
        Timestamp.from(Instant.now()));
  }

  private static Jornada mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Jornada(
        (UUID) rs.getObject("jornada_id"),
        (UUID) rs.getObject("tipo_jornada_id"),
        rs.getString("nombre"),
        rs.getObject("fecha", LocalDate.class),
        rs.getObject("hora_inicio", LocalTime.class),
        rs.getObject("hora_fin", LocalTime.class),
        rs.getString("lugar"),
        rs.getInt("cupo_total"),
        rs.getObject("fecha_limite_documentos", LocalDate.class),
        rs.getString("descripcion"),
        JornadaEstado.valueOf(rs.getString("estado")),
        rs.getString("motivo_cancelacion"),
        rs.getInt("version"));
  }
}

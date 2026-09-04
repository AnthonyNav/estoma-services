package com.estoma.registros.infrastructure.persistence;

import com.estoma.registros.domain.Registro;
import com.estoma.registros.domain.RegistroEstado;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRegistroRepository {

  private static final RowMapper<Registro> ROW_MAPPER = JdbcRegistroRepository::mapRow;

  private final JdbcTemplate jdbc;

  public JdbcRegistroRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean existeActivoParaAlumno(UUID alumnoId) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM registro WHERE alumno_id = ? "
                + "AND estado IN ('PENDIENTE_VALIDACION', 'CONFIRMADO'))",
            Boolean.class,
            alumnoId);
    return Boolean.TRUE.equals(exists);
  }

  public Registro insert(UUID jornadaId, UUID alumnoId) {
    UUID id = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO registro (registro_id, jornada_id, alumno_id, estado, version, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'PENDIENTE_VALIDACION', 1, ?, ?)",
        id, jornadaId, alumnoId, now, now);
    return findById(id).orElseThrow();
  }

  public Optional<Registro> findById(UUID registroId) {
    List<Registro> rows =
        jdbc.query(
            "SELECT registro_id, jornada_id, alumno_id, estado, motivo_rechazo, version "
                + "FROM registro WHERE registro_id = ?",
            ROW_MAPPER,
            registroId);
    return rows.stream().findFirst();
  }

  public List<Registro> findByJornada(UUID jornadaId) {
    return jdbc.query(
        "SELECT registro_id, jornada_id, alumno_id, estado, motivo_rechazo, version "
            + "FROM registro WHERE jornada_id = ? ORDER BY created_at",
        ROW_MAPPER,
        jornadaId);
  }

  public List<Registro> findAll() {
    return jdbc.query(
        "SELECT registro_id, jornada_id, alumno_id, estado, motivo_rechazo, version "
            + "FROM registro ORDER BY created_at DESC",
        ROW_MAPPER);
  }

  public Optional<Registro> confirmarSiPendiente(UUID registroId) {
    int updated =
        jdbc.update(
            "UPDATE registro SET estado = 'CONFIRMADO', version = version + 1, updated_at = ? "
                + "WHERE registro_id = ? AND estado = 'PENDIENTE_VALIDACION'",
            Timestamp.from(Instant.now()), registroId);
    return updated == 0 ? Optional.empty() : findById(registroId);
  }

  public Optional<Registro> rechazarSiPendiente(UUID registroId, String motivo) {
    int updated =
        jdbc.update(
            "UPDATE registro SET estado = 'RECHAZADO', motivo_rechazo = ?, version = version + 1, "
                + "updated_at = ? WHERE registro_id = ? AND estado = 'PENDIENTE_VALIDACION'",
            motivo, Timestamp.from(Instant.now()), registroId);
    return updated == 0 ? Optional.empty() : findById(registroId);
  }

  public Optional<Registro> cancelarPorAlumnoSiPendiente(UUID registroId) {
    int updated =
        jdbc.update(
            "UPDATE registro SET estado = 'CANCELADO_ALUMNO', version = version + 1, updated_at = ? "
                + "WHERE registro_id = ? AND estado = 'PENDIENTE_VALIDACION'",
            Timestamp.from(Instant.now()), registroId);
    return updated == 0 ? Optional.empty() : findById(registroId);
  }

  /** Cascade-cancels every active Registro of a cancelled Jornada; returns the affected rows. */
  public List<Registro> cancelarPorJornadaCancelada(UUID jornadaId) {
    return jdbc.query(
        "UPDATE registro SET estado = 'CANCELADO_JORNADA_CANCELADA', version = version + 1, updated_at = ? "
            + "WHERE jornada_id = ? AND estado IN ('PENDIENTE_VALIDACION', 'CONFIRMADO') "
            + "RETURNING registro_id, jornada_id, alumno_id, estado, motivo_rechazo, version",
        ROW_MAPPER,
        Timestamp.from(Instant.now()), jornadaId);
  }

  /** Completes every Confirmado Registro of a finalized Jornada; returns the affected rows. */
  public List<Registro> completarPorJornadaFinalizada(UUID jornadaId) {
    return jdbc.query(
        "UPDATE registro SET estado = 'COMPLETADO', version = version + 1, updated_at = ? "
            + "WHERE jornada_id = ? AND estado = 'CONFIRMADO' "
            + "RETURNING registro_id, jornada_id, alumno_id, estado, motivo_rechazo, version",
        ROW_MAPPER,
        Timestamp.from(Instant.now()), jornadaId);
  }

  private static Registro mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Registro(
        (UUID) rs.getObject("registro_id"),
        (UUID) rs.getObject("jornada_id"),
        (UUID) rs.getObject("alumno_id"),
        RegistroEstado.valueOf(rs.getString("estado")),
        rs.getString("motivo_rechazo"),
        rs.getInt("version"));
  }
}

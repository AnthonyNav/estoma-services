package com.estoma.jornadas.infrastructure.persistence;

import com.estoma.jornadas.domain.TipoJornada;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTipoJornadaRepository {

  private final JdbcTemplate jdbc;

  public JdbcTipoJornadaRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean existsActivo(UUID tipoJornadaId) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tipo_jornada WHERE tipo_jornada_id = ? AND activo)",
            Boolean.class,
            tipoJornadaId);
    return Boolean.TRUE.equals(exists);
  }

  public List<Integer> semestresPermitidos(UUID tipoJornadaId) {
    return jdbc.queryForList(
        "SELECT semestre FROM tipo_jornada_semestre_permitido WHERE tipo_jornada_id = ? ORDER BY semestre",
        Integer.class,
        tipoJornadaId);
  }

  public Optional<TipoJornada> findById(UUID tipoJornadaId) {
    List<TipoJornada> rows =
        jdbc.query(
            "SELECT tipo_jornada_id, nombre, activo, version FROM tipo_jornada WHERE tipo_jornada_id = ?",
            (rs, rowNum) ->
                new TipoJornada(
                    (UUID) rs.getObject("tipo_jornada_id"),
                    rs.getString("nombre"),
                    rs.getBoolean("activo"),
                    rs.getInt("version"),
                    semestresPermitidos((UUID) rs.getObject("tipo_jornada_id"))),
            tipoJornadaId);
    return rows.stream().findFirst();
  }

  @Transactional
  public TipoJornadaSaved upsert(UUID tipoJornadaId, String nombre, List<Integer> semestresPermitidos) {
    Timestamp now = Timestamp.from(Instant.now());
    UUID id = tipoJornadaId;
    int version;
    if (id == null) {
      id = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO tipo_jornada (tipo_jornada_id, nombre, activo, version, created_at, updated_at) "
              + "VALUES (?, ?, true, 1, ?, ?)",
          id, nombre, now, now);
      version = 1;
    } else {
      int updated =
          jdbc.update(
              "UPDATE tipo_jornada SET nombre = ?, version = version + 1, updated_at = ? WHERE tipo_jornada_id = ?",
              nombre, now, id);
      if (updated == 0) {
        throw new IllegalStateException("tipo_jornada not found: " + id);
      }
      version = jdbc.queryForObject("SELECT version FROM tipo_jornada WHERE tipo_jornada_id = ?", Integer.class, id);
      jdbc.update("DELETE FROM tipo_jornada_semestre_permitido WHERE tipo_jornada_id = ?", id);
    }

    UUID finalId = id;
    jdbc.batchUpdate(
        "INSERT INTO tipo_jornada_semestre_permitido (tipo_jornada_id, semestre) VALUES (?, ?)",
        semestresPermitidos,
        semestresPermitidos.size(),
        (ps, semestre) -> {
          ps.setObject(1, finalId);
          ps.setInt(2, semestre);
        });

    return new TipoJornadaSaved(id, version);
  }

  public record TipoJornadaSaved(UUID tipoJornadaId, int version) {}
}

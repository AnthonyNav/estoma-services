package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC implementation of {@link PersonStatusProjectionRepository}. */
@Repository
class JdbcPersonStatusProjectionRepository implements PersonStatusProjectionRepository {

  private final JdbcClient jdbc;

  JdbcPersonStatusProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PersonStatusProjectionRecord> findByPersonId(UUID personId) {
    return jdbc.sql("SELECT person_id, status, source_version, updated_at FROM person_status_projection WHERE person_id = :personId")
        .param("personId", personId)
        .query(JdbcPersonStatusProjectionRepository::mapRow)
        .optional();
  }

  @Override
  public boolean upsertIfNewer(UUID personId, String status, int sourceVersion, Instant updatedAt) {
    int rowsAffected =
        jdbc.sql(
                """
                INSERT INTO person_status_projection (person_id, status, source_version, updated_at)
                VALUES (:personId, CAST(:status AS person_status), :sourceVersion, :updatedAt)
                ON CONFLICT (person_id) DO UPDATE SET
                  status = EXCLUDED.status,
                  source_version = EXCLUDED.source_version,
                  updated_at = EXCLUDED.updated_at
                WHERE person_status_projection.source_version < EXCLUDED.source_version
                """)
            .param("personId", personId)
            .param("status", status)
            .param("sourceVersion", sourceVersion)
            .param("updatedAt", Timestamps.toTimestamp(updatedAt))
            .update();
    return rowsAffected == 1;
  }

  private static PersonStatusProjectionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new PersonStatusProjectionRecord(
        rs.getObject("person_id", UUID.class), rs.getString("status"), rs.getInt("source_version"), Timestamps.toInstant(rs, "updated_at"));
  }
}

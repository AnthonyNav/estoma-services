package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPersonStatusProjectionRepository implements PersonStatusProjectionRepository {

  private final JdbcClient jdbc;

  JdbcPersonStatusProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PersonStatusProjection> findByPersonId(UUID personId) {
    return jdbc.sql("SELECT person_id, status, source_version, updated_at FROM person_status_projection WHERE person_id = :personId")
        .param("personId", personId)
        .query(PersonProjectionRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public int upsertIfNewer(PersonStatusProjection projection) {
    return jdbc.sql(
            """
            INSERT INTO person_status_projection (person_id, status, source_version, updated_at)
            VALUES (:personId, CAST(:status AS projected_status), :sourceVersion, :updatedAt)
            ON CONFLICT (person_id) DO UPDATE SET
              status = EXCLUDED.status,
              source_version = EXCLUDED.source_version,
              updated_at = EXCLUDED.updated_at
            WHERE person_status_projection.source_version < EXCLUDED.source_version
            """)
        .param("personId", projection.personId())
        .param("status", projection.status().name())
        .param("sourceVersion", projection.sourceVersion())
        .param("updatedAt", Timestamps.toTimestamp(projection.updatedAt()))
        .update();
  }

  @Override
  public int clearAll() {
    return jdbc.sql("DELETE FROM person_status_projection").update();
  }
}

package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.CabinRepository;
import com.estoma.washadministration.domain.resource.Cabin;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCabinRepository implements CabinRepository {

  private static final String COLUMNS =
      "cabin_id, code, name, cabin_type, status, version, created_by_account_id, "
          + "updated_by_account_id, created_at, updated_at";
  private final JdbcClient jdbc;

  JdbcCabinRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Cabin> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM cabins WHERE cabin_id = :id")
        .param("id", id)
        .query(CabinRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<Cabin> findByCode(String code) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM cabins WHERE code = :code")
        .param("code", code)
        .query(CabinRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public void insert(Cabin cabin) {
    jdbc.sql(
            """
            INSERT INTO cabins (
              cabin_id, code, name, cabin_type, status, version,
              created_by_account_id, updated_by_account_id, created_at, updated_at
            ) VALUES (
              :id, :code, :name, CAST(:cabinType AS cabin_type), CAST(:status AS resource_status), :version,
              :createdBy, :updatedBy, :createdAt, :updatedAt
            )
            """)
        .param("id", cabin.id())
        .param("code", cabin.code().value())
        .param("name", cabin.name().value())
        .param("cabinType", cabin.cabinType().name())
        .param("status", cabin.status().name())
        .param("version", cabin.version())
        .param("createdBy", cabin.createdByAccountId())
        .param("updatedBy", cabin.updatedByAccountId())
        .param("createdAt", Timestamps.toTimestamp(cabin.createdAt()))
        .param("updatedAt", Timestamps.toTimestamp(cabin.updatedAt()))
        .update();
  }

  @Override
  public int update(Cabin cabin, int expectedVersion) {
    return jdbc.sql(
            """
            UPDATE cabins SET
              name = :name, cabin_type = CAST(:cabinType AS cabin_type),
              status = CAST(:status AS resource_status), version = :newVersion,
              updated_by_account_id = :updatedBy, updated_at = :updatedAt
             WHERE cabin_id = :id AND version = :expectedVersion
            """)
        .param("id", cabin.id())
        .param("name", cabin.name().value())
        .param("cabinType", cabin.cabinType().name())
        .param("status", cabin.status().name())
        .param("newVersion", cabin.version())
        .param("updatedBy", cabin.updatedByAccountId())
        .param("updatedAt", Timestamps.toTimestamp(cabin.updatedAt()))
        .param("expectedVersion", expectedVersion)
        .update();
  }
}

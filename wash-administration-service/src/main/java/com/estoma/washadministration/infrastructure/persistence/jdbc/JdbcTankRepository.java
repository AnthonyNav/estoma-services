package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.TankRepository;
import com.estoma.washadministration.domain.resource.Tank;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTankRepository implements TankRepository {

  private static final String COLUMNS =
      "tank_id, cabin_id, code, name, configured_capacity, status, version, created_by_account_id, "
          + "updated_by_account_id, created_at, updated_at";
  private final JdbcClient jdbc;

  JdbcTankRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Tank> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM tanks WHERE tank_id = :id")
        .param("id", id)
        .query(TankRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<Tank> findByCabinIdAndCode(UUID cabinId, String code) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM tanks WHERE cabin_id = :cabinId AND code = :code")
        .param("cabinId", cabinId)
        .param("code", code)
        .query(TankRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public long countByCabinId(UUID cabinId) {
    return jdbc.sql("SELECT count(*) FROM tanks WHERE cabin_id = :cabinId")
        .param("cabinId", cabinId)
        .query(Long.class)
        .single();
  }

  @Override
  public long countNonRetiredByCabinId(UUID cabinId) {
    return jdbc.sql("SELECT count(*) FROM tanks WHERE cabin_id = :cabinId AND status <> 'RETIRED'")
        .param("cabinId", cabinId)
        .query(Long.class)
        .single();
  }

  @Override
  public void insert(Tank tank) {
    jdbc.sql(
            """
            INSERT INTO tanks (
              tank_id, cabin_id, code, name, configured_capacity, status, version,
              created_by_account_id, updated_by_account_id, created_at, updated_at
            ) VALUES (
              :id, :cabinId, :code, :name, :capacity, CAST(:status AS resource_status), :version,
              :createdBy, :updatedBy, :createdAt, :updatedAt
            )
            """)
        .param("id", tank.id())
        .param("cabinId", tank.cabinId())
        .param("code", tank.code().value())
        .param("name", tank.name().value())
        .param("capacity", tank.configuredCapacity().value())
        .param("status", tank.status().name())
        .param("version", tank.version())
        .param("createdBy", tank.createdByAccountId())
        .param("updatedBy", tank.updatedByAccountId())
        .param("createdAt", Timestamps.toTimestamp(tank.createdAt()))
        .param("updatedAt", Timestamps.toTimestamp(tank.updatedAt()))
        .update();
  }

  @Override
  public int update(Tank tank, int expectedVersion) {
    return jdbc.sql(
            """
            UPDATE tanks SET
              name = :name, configured_capacity = :capacity, status = CAST(:status AS resource_status),
              version = :newVersion, updated_by_account_id = :updatedBy, updated_at = :updatedAt
             WHERE tank_id = :id AND version = :expectedVersion
            """)
        .param("id", tank.id())
        .param("name", tank.name().value())
        .param("capacity", tank.configuredCapacity().value())
        .param("status", tank.status().name())
        .param("newVersion", tank.version())
        .param("updatedBy", tank.updatedByAccountId())
        .param("updatedAt", Timestamps.toTimestamp(tank.updatedAt()))
        .param("expectedVersion", expectedVersion)
        .update();
  }
}

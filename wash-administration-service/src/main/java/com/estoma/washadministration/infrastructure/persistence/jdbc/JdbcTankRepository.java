package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.TankRepository;
import com.estoma.washadministration.domain.resource.Tank;
import com.estoma.washadministration.domain.shared.DomainErrorCode;
import com.estoma.washadministration.domain.shared.DomainRuleViolationException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
  @Transactional
  public void insert(Tank tank) {
    Optional<String> parentStatus =
        jdbc.sql("SELECT status::text FROM cabins WHERE cabin_id = :cabinId FOR UPDATE")
            .param("cabinId", tank.cabinId())
            .query(String.class)
            .optional();

    if (parentStatus.isPresent() && "RETIRED".equals(parentStatus.get())) {
      throw new DomainRuleViolationException(
          DomainErrorCode.CABIN_RETIRED, "cannot register a tank under a retired cabin");
    }

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
    OptimisticVersionGuard.requireNext(expectedVersion, tank.version());
    return jdbc.sql(
            """
            UPDATE tanks SET
              name = :name, configured_capacity = :capacity, status = CAST(:status AS resource_status),
              version = version + 1, updated_by_account_id = :updatedBy, updated_at = :updatedAt
             WHERE tank_id = :id AND version = :expectedVersion
            """)
        .param("id", tank.id())
        .param("name", tank.name().value())
        .param("capacity", tank.configuredCapacity().value())
        .param("status", tank.status().name())
        .param("updatedBy", tank.updatedByAccountId())
        .param("updatedAt", Timestamps.toTimestamp(tank.updatedAt()))
        .param("expectedVersion", expectedVersion)
        .update();
  }
}

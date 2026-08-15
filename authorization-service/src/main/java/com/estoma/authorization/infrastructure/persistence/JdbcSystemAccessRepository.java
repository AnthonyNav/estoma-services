package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.SystemAccessRepository;
import com.estoma.authorization.domain.model.SystemAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSystemAccessRepository implements SystemAccessRepository {

  private static final String COLUMNS =
      "sa.system_access_id, sa.account_id, sa.system_id, s.code AS system_code, sa.status, "
          + "sa.reason_code, sa.reason_detail, sa.version, "
          + "sa.granted_by_account_id, sa.granted_by_actor_type, sa.granted_by_actor_service, "
          + "sa.suspended_by_account_id, sa.suspended_by_actor_type, sa.suspended_by_actor_service, "
          + "sa.revoked_by_account_id, sa.revoked_by_actor_type, sa.revoked_by_actor_service, "
          + "sa.restored_by_account_id, sa.restored_by_actor_type, sa.restored_by_actor_service, "
          + "sa.granted_at, sa.suspended_at, sa.revoked_at, sa.updated_at";
  private final JdbcClient jdbc;

  JdbcSystemAccessRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SystemAccess> findById(UUID systemAccessId) {
    return jdbc.sql(select() + " WHERE sa.system_access_id = :systemAccessId")
        .param("systemAccessId", systemAccessId)
        .query(SystemAccessRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<SystemAccess> findByAccountIdAndSystemId(UUID accountId, UUID systemId) {
    return jdbc.sql(select() + " WHERE sa.account_id = :accountId AND sa.system_id = :systemId")
        .param("accountId", accountId)
        .param("systemId", systemId)
        .query(SystemAccessRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public List<SystemAccess> findByAccountId(UUID accountId) {
    return jdbc.sql(select() + " WHERE sa.account_id = :accountId ORDER BY sa.system_id")
        .param("accountId", accountId)
        .query(SystemAccessRowMapper.INSTANCE)
        .list();
  }

  @Override
  public void insert(SystemAccess access) {
    jdbc.sql(
            """
            INSERT INTO system_accesses (
              system_access_id, account_id, system_id, status, reason_code, reason_detail, version,
              granted_by_account_id, granted_by_actor_type, granted_by_actor_service,
              suspended_by_account_id, suspended_by_actor_type, suspended_by_actor_service,
              revoked_by_account_id, revoked_by_actor_type, revoked_by_actor_service,
              restored_by_account_id, restored_by_actor_type, restored_by_actor_service,
              granted_at, suspended_at, revoked_at, updated_at
            ) VALUES (
              :systemAccessId, :accountId, :systemId, CAST(:status AS system_access_status), :reasonCode, :reasonDetail, :version,
              :grantedByAccountId, CAST(:grantedByActorType AS actor_type), :grantedByActorService,
              :suspendedByAccountId, CAST(:suspendedByActorType AS actor_type), :suspendedByActorService,
              :revokedByAccountId, CAST(:revokedByActorType AS actor_type), :revokedByActorService,
              :restoredByAccountId, CAST(:restoredByActorType AS actor_type), :restoredByActorService,
              :grantedAt, :suspendedAt, :revokedAt, :updatedAt
            )
            """)
        .param("systemAccessId", access.systemAccessId())
        .param("accountId", access.accountId())
        .param("systemId", access.systemId())
        .param("status", access.status().name())
        .param("reasonCode", access.reasonCode() == null ? null : access.reasonCode().name())
        .param("reasonDetail", access.reasonDetail())
        .param("version", access.version())
        .param("grantedByAccountId", access.grantedBy().accountId())
        .param("grantedByActorType", access.grantedBy().type().name())
        .param("grantedByActorService", access.grantedBy().service())
        .param("suspendedByAccountId", access.suspendedBy() == null ? null : access.suspendedBy().accountId())
        .param("suspendedByActorType", access.suspendedBy() == null ? null : access.suspendedBy().type().name())
        .param("suspendedByActorService", access.suspendedBy() == null ? null : access.suspendedBy().service())
        .param("revokedByAccountId", access.revokedBy() == null ? null : access.revokedBy().accountId())
        .param("revokedByActorType", access.revokedBy() == null ? null : access.revokedBy().type().name())
        .param("revokedByActorService", access.revokedBy() == null ? null : access.revokedBy().service())
        .param("restoredByAccountId", access.restoredBy() == null ? null : access.restoredBy().accountId())
        .param("restoredByActorType", access.restoredBy() == null ? null : access.restoredBy().type().name())
        .param("restoredByActorService", access.restoredBy() == null ? null : access.restoredBy().service())
        .param("grantedAt", Timestamps.toTimestamp(access.grantedAt()))
        .param("suspendedAt", Timestamps.toTimestamp(access.suspendedAt()))
        .param("revokedAt", Timestamps.toTimestamp(access.revokedAt()))
        .param("updatedAt", Timestamps.toTimestamp(access.updatedAt()))
        .update();
  }

  @Override
  public boolean insertIfAbsent(SystemAccess access) {
    return jdbc.sql(
            """
            INSERT INTO system_accesses (
              system_access_id, account_id, system_id, status, reason_code, reason_detail, version,
              granted_by_account_id, granted_by_actor_type, granted_by_actor_service,
              suspended_by_account_id, suspended_by_actor_type, suspended_by_actor_service,
              revoked_by_account_id, revoked_by_actor_type, revoked_by_actor_service,
              restored_by_account_id, restored_by_actor_type, restored_by_actor_service,
              granted_at, suspended_at, revoked_at, updated_at
            ) VALUES (
              :systemAccessId, :accountId, :systemId, CAST(:status AS system_access_status), :reasonCode, :reasonDetail, :version,
              :grantedByAccountId, CAST(:grantedByActorType AS actor_type), :grantedByActorService,
              :suspendedByAccountId, CAST(:suspendedByActorType AS actor_type), :suspendedByActorService,
              :revokedByAccountId, CAST(:revokedByActorType AS actor_type), :revokedByActorService,
              :restoredByAccountId, CAST(:restoredByActorType AS actor_type), :restoredByActorService,
              :grantedAt, :suspendedAt, :revokedAt, :updatedAt
            ) ON CONFLICT (account_id, system_id) DO NOTHING
            """)
        .param("systemAccessId", access.systemAccessId())
        .param("accountId", access.accountId())
        .param("systemId", access.systemId())
        .param("status", access.status().name())
        .param("reasonCode", access.reasonCode() == null ? null : access.reasonCode().name())
        .param("reasonDetail", access.reasonDetail())
        .param("version", access.version())
        .param("grantedByAccountId", access.grantedBy().accountId())
        .param("grantedByActorType", access.grantedBy().type().name())
        .param("grantedByActorService", access.grantedBy().service())
        .param("suspendedByAccountId", access.suspendedBy() == null ? null : access.suspendedBy().accountId())
        .param("suspendedByActorType", access.suspendedBy() == null ? null : access.suspendedBy().type().name())
        .param("suspendedByActorService", access.suspendedBy() == null ? null : access.suspendedBy().service())
        .param("revokedByAccountId", access.revokedBy() == null ? null : access.revokedBy().accountId())
        .param("revokedByActorType", access.revokedBy() == null ? null : access.revokedBy().type().name())
        .param("revokedByActorService", access.revokedBy() == null ? null : access.revokedBy().service())
        .param("restoredByAccountId", access.restoredBy() == null ? null : access.restoredBy().accountId())
        .param("restoredByActorType", access.restoredBy() == null ? null : access.restoredBy().type().name())
        .param("restoredByActorService", access.restoredBy() == null ? null : access.restoredBy().service())
        .param("grantedAt", Timestamps.toTimestamp(access.grantedAt()))
        .param("suspendedAt", Timestamps.toTimestamp(access.suspendedAt()))
        .param("revokedAt", Timestamps.toTimestamp(access.revokedAt()))
        .param("updatedAt", Timestamps.toTimestamp(access.updatedAt()))
        .update()
        == 1;
  }

  @Override
  public int update(SystemAccess access, int expectedVersion) {
    OptimisticVersionGuard.requireNext(expectedVersion, access.version());
    return jdbc.sql(
            """
            UPDATE system_accesses SET
              status = CAST(:status AS system_access_status), reason_code = :reasonCode, reason_detail = :reasonDetail,
              version = version + 1,
              suspended_by_account_id = :suspendedByAccountId,
              suspended_by_actor_type = CAST(:suspendedByActorType AS actor_type),
              suspended_by_actor_service = :suspendedByActorService,
              revoked_by_account_id = :revokedByAccountId,
              revoked_by_actor_type = CAST(:revokedByActorType AS actor_type),
              revoked_by_actor_service = :revokedByActorService,
              restored_by_account_id = :restoredByAccountId,
              restored_by_actor_type = CAST(:restoredByActorType AS actor_type),
              restored_by_actor_service = :restoredByActorService,
              suspended_at = :suspendedAt, revoked_at = :revokedAt, updated_at = :updatedAt
            WHERE system_access_id = :systemAccessId AND version = :expectedVersion
            """)
        .param("systemAccessId", access.systemAccessId())
        .param("status", access.status().name())
        .param("reasonCode", access.reasonCode() == null ? null : access.reasonCode().name())
        .param("reasonDetail", access.reasonDetail())
        .param("suspendedByAccountId", access.suspendedBy() == null ? null : access.suspendedBy().accountId())
        .param("suspendedByActorType", access.suspendedBy() == null ? null : access.suspendedBy().type().name())
        .param("suspendedByActorService", access.suspendedBy() == null ? null : access.suspendedBy().service())
        .param("revokedByAccountId", access.revokedBy() == null ? null : access.revokedBy().accountId())
        .param("revokedByActorType", access.revokedBy() == null ? null : access.revokedBy().type().name())
        .param("revokedByActorService", access.revokedBy() == null ? null : access.revokedBy().service())
        .param("restoredByAccountId", access.restoredBy() == null ? null : access.restoredBy().accountId())
        .param("restoredByActorType", access.restoredBy() == null ? null : access.restoredBy().type().name())
        .param("restoredByActorService", access.restoredBy() == null ? null : access.restoredBy().service())
        .param("suspendedAt", Timestamps.toTimestamp(access.suspendedAt()))
        .param("revokedAt", Timestamps.toTimestamp(access.revokedAt()))
        .param("updatedAt", Timestamps.toTimestamp(access.updatedAt()))
        .param("expectedVersion", expectedVersion)
        .update();
  }

  private static String select() {
    return "SELECT " + COLUMNS + " FROM system_accesses sa JOIN systems s ON s.system_id = sa.system_id";
  }
}

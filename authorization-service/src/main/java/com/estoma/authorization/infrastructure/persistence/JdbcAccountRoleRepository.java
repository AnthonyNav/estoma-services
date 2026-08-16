package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.exception.AuthorizationErrorCode;
import com.estoma.authorization.application.exception.OptimisticLockConflictException;
import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.domain.model.AccountRoleAssignment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountRoleRepository implements AccountRoleRepository {

  private static final String COLUMNS =
      "ar.account_id, ar.role_id, r.code AS role_code, ar.version, "
          + "ar.assigned_by_account_id, ar.assigned_by_actor_type, ar.assigned_by_actor_service, "
          + "ar.changed_by_account_id, ar.changed_by_actor_type, ar.changed_by_actor_service, "
          + "ar.assigned_at, ar.updated_at";
  private final JdbcClient jdbc;

  JdbcAccountRoleRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AccountRoleAssignment> findByAccountId(UUID accountId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM account_roles ar JOIN roles r ON r.role_id = ar.role_id "
                + "WHERE ar.account_id = :accountId")
        .param("accountId", accountId)
        .query(AccountRoleRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public void insert(AccountRoleAssignment assignment) {
    jdbc.sql(
            """
            INSERT INTO account_roles (
              account_id, role_id, version, assigned_by_account_id, assigned_by_actor_type,
              assigned_by_actor_service, assigned_at, updated_at
            ) VALUES (
              :accountId, :roleId, :version, :assignedByAccountId, CAST(:assignedByActorType AS actor_type),
              :assignedByActorService, :assignedAt, :updatedAt
            )
            """)
        .param("accountId", assignment.accountId())
        .param("roleId", assignment.roleId())
        .param("version", assignment.version())
        .param("assignedByAccountId", assignment.assignedBy().accountId())
        .param("assignedByActorType", assignment.assignedBy().type().name())
        .param("assignedByActorService", assignment.assignedBy().service())
        .param("assignedAt", Timestamps.toTimestamp(assignment.assignedAt()))
        .param("updatedAt", Timestamps.toTimestamp(assignment.updatedAt()))
        .update();
  }

  @Override
  public boolean insertIfAbsent(AccountRoleAssignment assignment) {
    return jdbc.sql(
            """
            INSERT INTO account_roles (
              account_id, role_id, version, assigned_by_account_id, assigned_by_actor_type,
              assigned_by_actor_service, assigned_at, updated_at
            ) VALUES (
              :accountId, :roleId, :version, :assignedByAccountId, CAST(:assignedByActorType AS actor_type),
              :assignedByActorService, :assignedAt, :updatedAt
            ) ON CONFLICT (account_id) DO NOTHING
            """)
        .param("accountId", assignment.accountId())
        .param("roleId", assignment.roleId())
        .param("version", assignment.version())
        .param("assignedByAccountId", assignment.assignedBy().accountId())
        .param("assignedByActorType", assignment.assignedBy().type().name())
        .param("assignedByActorService", assignment.assignedBy().service())
        .param("assignedAt", Timestamps.toTimestamp(assignment.assignedAt()))
        .param("updatedAt", Timestamps.toTimestamp(assignment.updatedAt()))
        .update()
        == 1;
  }

  @Override
  public int update(AccountRoleAssignment assignment, int expectedVersion) {
    OptimisticVersionGuard.requireNext(expectedVersion, assignment.version());
    int updated = jdbc.sql(
            """
            UPDATE account_roles SET
              role_id = :roleId,
              version = version + 1,
              changed_by_account_id = :changedByAccountId,
              changed_by_actor_type = CAST(:changedByActorType AS actor_type),
              changed_by_actor_service = :changedByActorService,
              updated_at = :updatedAt
            WHERE account_id = :accountId AND version = :expectedVersion
            """)
        .param("accountId", assignment.accountId())
        .param("roleId", assignment.roleId())
        .param("changedByAccountId", assignment.changedBy() == null ? null : assignment.changedBy().accountId())
        .param("changedByActorType", assignment.changedBy() == null ? null : assignment.changedBy().type().name())
        .param("changedByActorService", assignment.changedBy() == null ? null : assignment.changedBy().service())
        .param("updatedAt", Timestamps.toTimestamp(assignment.updatedAt()))
        .param("expectedVersion", expectedVersion)
        .update();
    if (updated != 1) {
      throw new OptimisticLockConflictException(
          AuthorizationErrorCode.ACCOUNT_ROLE_VERSION_CONFLICT,
          "account role changed concurrently");
    }
    return updated;
  }
}

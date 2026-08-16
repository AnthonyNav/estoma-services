package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.AuthorizationSnapshotRepository;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccessStatus;
import com.estoma.authorization.domain.model.SystemCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAuthorizationSnapshotRepository implements AuthorizationSnapshotRepository {

  private final JdbcClient jdbc;

  JdbcAuthorizationSnapshotRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AccountAuthorizationSnapshot> find(UUID accountId, SystemCode systemCode) {
    RoleSnapshot role = jdbc.sql(
            "SELECT r.code, ar.version FROM account_roles ar JOIN roles r ON r.role_id = ar.role_id "
                + "WHERE ar.account_id = :accountId")
        .param("accountId", accountId)
        .query((rs, rowNum) -> new RoleSnapshot(RoleCode.parse(rs.getString("code")), rs.getInt("version")))
        .optional()
        .orElse(null);

    String accessSql =
        "SELECT sa.system_access_id, sa.system_id, s.code AS system_code, sa.status, sa.version "
            + "FROM system_accesses sa JOIN systems s ON s.system_id = sa.system_id "
            + "WHERE sa.account_id = :accountId";
    JdbcClient.StatementSpec accessStatement = jdbc.sql(
        systemCode == null ? accessSql + " ORDER BY s.code" : accessSql + " AND s.code = :systemCode ORDER BY s.code")
        .param("accountId", accountId);
    if (systemCode != null) {
      accessStatement = accessStatement.param("systemCode", systemCode.name());
    }
    List<AccessSnapshot> accesses = accessStatement
        .query((rs, rowNum) -> new AccessSnapshot(
            rs.getObject("system_access_id", UUID.class),
            rs.getObject("system_id", UUID.class),
            SystemCode.valueOf(rs.getString("system_code")),
            SystemAccessStatus.valueOf(rs.getString("status")),
            rs.getInt("version")))
        .list();

    if (role == null && accesses.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new AccountAuthorizationSnapshot(accountId, role, accesses));
  }

  @Override
  public SnapshotPage list(String cursor, int limit, SystemCode systemCode) {
    UUID cursorId = cursor == null || cursor.isBlank() ? null : UUID.fromString(cursor);
    int fetchLimit = Math.addExact(limit, 1);

    String population;
    if (systemCode == null) {
      population =
          "SELECT account_id FROM account_roles UNION SELECT account_id FROM system_accesses";
    } else {
      population =
          "SELECT ar.account_id FROM account_roles ar "
              + "JOIN role_systems rs ON rs.role_id = ar.role_id "
              + "JOIN systems s ON s.system_id = rs.system_id WHERE s.code = :systemCode "
              + "UNION SELECT sa.account_id FROM system_accesses sa "
              + "JOIN systems s2 ON s2.system_id = sa.system_id WHERE s2.code = :systemCode";
    }
    String sql =
        "SELECT account_id FROM (" + population + ") population "
            + (cursorId == null ? "" : "WHERE account_id > :cursor ")
            + "ORDER BY account_id LIMIT :limit";
    JdbcClient.StatementSpec statement = jdbc.sql(sql).param("limit", fetchLimit);
    if (systemCode != null) {
      statement = statement.param("systemCode", systemCode.name());
    }
    if (cursorId != null) {
      statement = statement.param("cursor", cursorId);
    }
    List<UUID> ids = statement.query(UUID.class).list();
    boolean hasMore = ids.size() > limit;
    List<UUID> pageIds = hasMore ? ids.subList(0, limit) : ids;
    List<AccountAuthorizationSnapshot> items = new ArrayList<>(pageIds.size());
    for (UUID accountId : pageIds) {
      find(accountId, systemCode).ifPresent(items::add);
    }
    String nextCursor = hasMore && !pageIds.isEmpty()
        ? pageIds.get(pageIds.size() - 1).toString()
        : null;
    return new SnapshotPage(items, nextCursor, hasMore);
  }
}

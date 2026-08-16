package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.AccountRoleAssignment;
import java.util.Optional;
import java.util.UUID;

public interface AccountRoleRepository {

  Optional<AccountRoleAssignment> findByAccountId(UUID accountId);

  void insert(AccountRoleAssignment assignment);

  default boolean insertIfAbsent(AccountRoleAssignment assignment) {
    insert(assignment);
    return true;
  }

  int update(AccountRoleAssignment assignment, int expectedVersion);
}

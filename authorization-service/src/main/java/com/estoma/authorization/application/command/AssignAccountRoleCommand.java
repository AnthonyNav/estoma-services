package com.estoma.authorization.application.command;

import com.estoma.authorization.domain.model.RoleCode;
import java.util.Objects;
import java.util.UUID;

public record AssignAccountRoleCommand(UUID accountId, RoleCode roleCode, ImportExecution importExecution)
    implements AuthorizationCommand {

  public AssignAccountRoleCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(roleCode, "roleCode");
  }

  @Override public String operationType() { return "AssignAccountRole"; }
  @Override public UUID targetAccountId() { return accountId; }
}

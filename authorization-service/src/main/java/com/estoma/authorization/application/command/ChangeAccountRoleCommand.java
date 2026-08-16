package com.estoma.authorization.application.command;

import com.estoma.authorization.domain.model.RoleCode;
import java.util.Objects;
import java.util.UUID;

public record ChangeAccountRoleCommand(UUID accountId, RoleCode newRoleCode, int expectedVersion)
    implements AuthorizationCommand {

  public ChangeAccountRoleCommand {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(newRoleCode, "newRoleCode");
    if (expectedVersion <= 0) {
      throw new IllegalArgumentException("expectedVersion must be positive");
    }
  }

  @Override public String operationType() { return "ChangeAccountRole"; }
  @Override public UUID targetAccountId() { return accountId; }
  @Override public ImportExecution importExecution() { return null; }
}

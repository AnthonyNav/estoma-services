package com.estoma.authorization.application.command;

import com.estoma.authorization.domain.model.SystemCode;
import java.util.Objects;
import java.util.UUID;

public record GrantSystemAccessCommand(
    UUID systemAccessId, UUID accountId, SystemCode systemCode, ImportExecution importExecution)
    implements AuthorizationCommand {

  public GrantSystemAccessCommand {
    Objects.requireNonNull(systemAccessId, "systemAccessId");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(systemCode, "systemCode");
  }

  @Override public String operationType() { return "GrantSystemAccess"; }
  @Override public UUID targetAccountId() { return accountId; }
}

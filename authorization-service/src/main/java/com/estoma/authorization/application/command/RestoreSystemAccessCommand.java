package com.estoma.authorization.application.command;

import java.util.Objects;
import java.util.UUID;

public record RestoreSystemAccessCommand(UUID systemAccessId, int expectedVersion)
    implements AuthorizationCommand {

  public RestoreSystemAccessCommand {
    Objects.requireNonNull(systemAccessId, "systemAccessId");
    if (expectedVersion <= 0) {
      throw new IllegalArgumentException("expectedVersion must be positive");
    }
  }

  @Override public String operationType() { return "RestoreSystemAccess"; }
  @Override public UUID targetAccountId() { return null; }
  @Override public ImportExecution importExecution() { return null; }
}

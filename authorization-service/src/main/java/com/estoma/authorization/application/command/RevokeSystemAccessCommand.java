package com.estoma.authorization.application.command;

import com.estoma.authorization.domain.model.SystemAccessReasonCode;
import java.util.Objects;
import java.util.UUID;

public record RevokeSystemAccessCommand(
    UUID systemAccessId, int expectedVersion, SystemAccessReasonCode reasonCode, String reasonDetail)
    implements AuthorizationCommand {

  public RevokeSystemAccessCommand {
    Objects.requireNonNull(systemAccessId, "systemAccessId");
    Objects.requireNonNull(reasonCode, "reasonCode");
    if (expectedVersion <= 0) {
      throw new IllegalArgumentException("expectedVersion must be positive");
    }
  }

  @Override public String operationType() { return "RevokeSystemAccess"; }
  @Override public UUID targetAccountId() { return null; }
  @Override public ImportExecution importExecution() { return null; }
}

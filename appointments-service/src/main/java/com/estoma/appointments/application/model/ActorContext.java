package com.estoma.appointments.application.model;

import java.util.Objects;
import java.util.UUID;

/** Identity and local authorization attributes carried by an application command. */
public record ActorContext(UUID accountId, String roleCode, String systemCode) {
  public ActorContext {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(roleCode, "roleCode");
    Objects.requireNonNull(systemCode, "systemCode");
  }

  public boolean isStudent() {
    return "STUDENT".equals(roleCode);
  }
}

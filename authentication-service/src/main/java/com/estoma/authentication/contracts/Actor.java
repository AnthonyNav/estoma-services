package com.estoma.authentication.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** The actor an envelope carries responsibility for. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Actor(String type, UUID accountId, String service, String scope, String systemId) {

  public Actor(UUID accountId) {
    this("ACCOUNT", accountId, null, null, null);
  }

  public boolean isSystemService(String expectedService) {
    return "SYSTEM".equals(type) && expectedService.equals(service);
  }
}

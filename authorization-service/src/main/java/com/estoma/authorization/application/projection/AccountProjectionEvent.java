package com.estoma.authorization.application.projection;

import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import java.util.Objects;
import java.util.UUID;

public record AccountProjectionEvent(
    ProjectionEventMetadata metadata,
    UUID accountId,
    UUID personId,
    LoginType loginType,
    ProjectedStatus status,
    String payloadJson,
    boolean causallyApplicable)
    implements ProjectionEvent {

  public AccountProjectionEvent {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(loginType, "loginType");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(payloadJson, "payloadJson");
  }
}

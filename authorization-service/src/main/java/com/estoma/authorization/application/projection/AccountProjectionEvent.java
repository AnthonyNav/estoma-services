package com.estoma.authorization.application.projection;

import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal Account projection event. loginType is required only by AccountCreated; status transition
 * events do not carry it in the canonical Authentication contract and preserve the projected value.
 */
public record AccountProjectionEvent(
    ProjectionEventMetadata metadata,
    UUID accountId,
    UUID personId,
    LoginType loginType,
    ProjectedStatus status,
    String payloadJson)
    implements ProjectionEvent {

  public AccountProjectionEvent {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(payloadJson, "payloadJson");
  }
}

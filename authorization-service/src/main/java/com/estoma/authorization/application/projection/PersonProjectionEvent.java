package com.estoma.authorization.application.projection;

import com.estoma.authorization.domain.model.ProjectedStatus;
import java.util.Objects;
import java.util.UUID;

public record PersonProjectionEvent(
    ProjectionEventMetadata metadata,
    UUID personId,
    ProjectedStatus status,
    String payloadJson)
    implements ProjectionEvent {

  public PersonProjectionEvent {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(payloadJson, "payloadJson");
  }
}

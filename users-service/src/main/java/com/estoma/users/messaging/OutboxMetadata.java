package com.estoma.users.messaging;

import com.estoma.users.contract.Actor;
import java.util.UUID;

/** Transport metadata retained outside the business payload until Outbox relay. */
public record OutboxMetadata(
    String destination,
    UUID operationId,
    String idempotencyKey,
    Actor actor) {

  /** Backward-compatible constructor for Events and non-coordinated Query Results. */
  public OutboxMetadata(String destination, UUID operationId, Actor actor) {
    this(destination, operationId, null, actor);
  }
}

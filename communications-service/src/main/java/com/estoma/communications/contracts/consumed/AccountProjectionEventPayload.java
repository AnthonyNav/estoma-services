package com.estoma.communications.contracts.consumed;

import java.util.UUID;

/**
 * Conceptual payload shared by {@code AccountCreated}/{@code
 * AccountActivated}/{@code AccountDeactivated}/{@code
 * LoginIdentifierChanged}, mirroring {@code authentication-service}'s own
 * {@code AccountCreatedPayload} shape (which already carries {@code
 * personId}). Not yet formalized against what {@code authentication-service}
 * actually publishes (execution_plan.md Phase 3). This is the event that
 * creates a {@code recipient_projection} row -- it is the only consumed
 * event that carries a concrete {@code accountId}.
 */
public record AccountProjectionEventPayload(UUID accountId, UUID personId, String status, int sourceVersion) {}

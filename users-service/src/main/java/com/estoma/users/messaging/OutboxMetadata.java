package com.estoma.users.messaging;

import com.estoma.users.contract.Actor;
import java.util.UUID;

/**
 * The shape of {@code outbox_messages.metadata} — the declared schema
 * delta from data-model.md (docs/system-contract.md §8, §10 D7): {@code
 * operationId} and the actor are not derivable from the pure contract
 * {@code payload} at relay time, so they travel here instead.
 *
 * <p>{@code actor} carries the full {@link Actor} (ACCOUNT or SYSTEM), not
 * just an account id — an Event or Result produced on behalf of a
 * technical actor must be able to say so on the wire, not silently drop
 * to a null actor.
 *
 * <p>{@code destination} is only meaningful for RESULT rows (the {@code
 * producer} of the command/query being answered — see {@code
 * RoutingKeyResolver#resolveResult}); {@code operationId} is only carried
 * on RESULT rows (absent on EVENT rows, docs/system-contract.md §10 D6).
 */
public record OutboxMetadata(String destination, UUID operationId, Actor actor) {}

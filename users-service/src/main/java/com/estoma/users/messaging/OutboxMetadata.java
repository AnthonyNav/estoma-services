package com.estoma.users.messaging;

import java.util.UUID;

/**
 * The shape of {@code outbox_messages.metadata} — the declared schema
 * delta from data-model.md (docs/system-contract.md §8, §10 D7): {@code
 * operationId} and the actor are not derivable from the pure contract
 * {@code payload} at relay time, so they travel here instead.
 *
 * <p>{@code destination} is only meaningful for RESULT rows (the {@code
 * producer} of the command being answered — see {@code RoutingKeyResolver
 * #resolveResult}); {@code operationId} is only carried on RESULT rows
 * (absent on EVENT rows, docs/system-contract.md §10 D6).
 */
public record OutboxMetadata(String destination, UUID operationId, UUID actorAccountId) {}

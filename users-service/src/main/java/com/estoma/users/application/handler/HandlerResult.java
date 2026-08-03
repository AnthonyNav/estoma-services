package com.estoma.users.application.handler;

import java.util.UUID;

/**
 * What a successful {@link PersonCommandHandler#handle} produces. {@code
 * PersonCommandDispatcher} uses this to build both outbox rows generically:
 * the RESULT payload is always {@code {personId: aggregateId, version:
 * aggregateVersion}} for all four commands (docs/system-contract.md §4),
 * so only the EVENT payload and its messageType need to come from the
 * handler.
 */
public record HandlerResult(UUID aggregateId, int aggregateVersion, Object eventPayload, String eventMessageType) {}

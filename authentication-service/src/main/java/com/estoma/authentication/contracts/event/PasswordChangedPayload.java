package com.estoma.authentication.contracts.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code PasswordChanged} (events.md §4). Never includes the
 * previous password, the new password, the hash, or hash parameters.
 */
public record PasswordChangedPayload(UUID accountId, String changeSource, Instant changedAt, boolean sessionsRevoked) {}

package com.estoma.authentication.contracts.event;

import com.estoma.authentication.contracts.LoginType;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code LoginIdentifierChanged} (events.md §3). Does not
 * include the full identifier -- consumers that need it are not yet
 * documented as requiring it.
 */
public record LoginIdentifierChangedPayload(UUID accountId, LoginType loginType, String changeSource, Instant changedAt) {}

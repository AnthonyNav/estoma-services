package com.estoma.communications.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** {@code InboxVisited} (events.md §2, §4). */
public record InboxVisitedPayload(UUID accountId, String systemCode, Instant visitedAt) {}

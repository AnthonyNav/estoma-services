package com.estoma.communications.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** {@code InboxMessageExpired}, published once per expired message (commands.md §2, events.md §2). */
public record InboxMessageExpiredPayload(UUID inboxMessageId, UUID communicationId, Instant expiredAt) {}

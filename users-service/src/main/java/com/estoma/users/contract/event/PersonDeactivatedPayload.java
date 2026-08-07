package com.estoma.users.contract.event;

import java.time.Instant;
import java.util.UUID;

/** Payload of the {@code PersonDeactivated} event (events.md §5). */
public record PersonDeactivatedPayload(UUID personId, String status, int version, Instant deactivatedAt) {}

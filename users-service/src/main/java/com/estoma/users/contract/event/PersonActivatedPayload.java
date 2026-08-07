package com.estoma.users.contract.event;

import java.time.Instant;
import java.util.UUID;

/** Payload of the {@code PersonActivated} event (events.md §4). */
public record PersonActivatedPayload(UUID personId, String status, int version, Instant activatedAt) {}

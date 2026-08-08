package com.estoma.authentication.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** Payload of {@code AccountActivated} (events.md §3). Never assigns roles or access. */
public record AccountActivatedPayload(UUID accountId, UUID personId, String status, int version, Instant activatedAt) {}

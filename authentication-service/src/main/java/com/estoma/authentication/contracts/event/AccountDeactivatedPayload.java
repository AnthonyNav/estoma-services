package com.estoma.authentication.contracts.event;

import java.time.Instant;
import java.util.UUID;

/** Payload of {@code AccountDeactivated} (events.md §3). Always accompanied by {@code AllAccountSessionsRevoked}. */
public record AccountDeactivatedPayload(
    UUID accountId, String previousStatus, String status, String reasonCode, String reasonDetail, Instant deactivatedAt) {}

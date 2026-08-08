package com.estoma.authentication.contracts.event;

import com.estoma.authentication.contracts.LoginType;
import java.time.Instant;
import java.util.UUID;

/** Payload of {@code AccountCreated} (events.md §3). Status is always INACTIVE at creation. */
public record AccountCreatedPayload(
    UUID accountId, UUID personId, LoginType loginType, String status, int version, Instant createdAt) {}

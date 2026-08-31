package com.estoma.authentication.contracts.event;

import com.estoma.authentication.contracts.LoginType;
import java.time.Instant;
import java.util.UUID;

/**
 * Owner-authoritative, non-secret Account presentation update for BFF
 * projections. Its envelope {@code aggregateVersion} is the source version.
 */
public record AccountPresentationUpdatedPayload(
    UUID accountId,
    UUID personId,
    LoginType loginType,
    String loginIdentifier,
    String accountStatus,
    Instant updatedAt) {}

package com.estoma.authentication.domain.account;

import com.estoma.authentication.contracts.AccountStatus;
import com.estoma.authentication.contracts.Actor;
import java.time.Instant;
import java.util.UUID;

/** Append-only audit record for an account lifecycle transition. */
public record AccountStatusChange(
    UUID historyId,
    UUID accountId,
    AccountStatus previousStatus,
    AccountStatus newStatus,
    String reasonCode,
    String reasonDetail,
    Actor actor,
    UUID correlationId,
    Instant occurredAt,
    int accountVersion) {}

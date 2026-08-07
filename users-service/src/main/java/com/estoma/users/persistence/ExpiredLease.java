package com.estoma.users.persistence;

import java.util.UUID;

/**
 * One {@code outbox_messages} row whose {@code lease_until} has passed —
 * a relay worker claimed it and never marked it published or released,
 * most likely because it crashed or was interrupted mid-batch. {@code
 * OutboxSweeper} reclaims these one at a time, each through the same
 * fenced transitions the relay itself uses (docs/system-contract.md §8).
 */
public record ExpiredLease(UUID messageId, UUID claimToken, int attempts) {}

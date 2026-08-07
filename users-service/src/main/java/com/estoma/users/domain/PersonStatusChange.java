package com.estoma.users.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One append-only row in {@code person_status_history}. Not itself a
 * domain aggregate — it exists to record every activation/deactivation
 * (data-model.md §7).
 *
 * <p>{@code changedByAccountId} is a logical reference to an
 * authentication-service Account (no physical FK — data-model.md §7)
 * carrying whichever actor's command produced this transition.
 */
public record PersonStatusChange(
    UUID statusChangeId,
    UUID personId,
    PersonStatus previousStatus,
    PersonStatus newStatus,
    UUID changedByAccountId,
    UUID correlationId,
    Instant changedAt) {}

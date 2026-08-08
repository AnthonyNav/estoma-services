package com.estoma.users.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One append-only row in {@code person_status_history}. Not itself a
 * domain aggregate — it exists to record every activation/deactivation
 * (data-model.md §7).
 *
 * <p>{@code reasonCode}/{@code reasonDetail} are only meaningful for a
 * deactivation ({@code newStatus == INACTIVE}) — {@code null} for an
 * activation. {@code reasonCode} is mandatory whenever {@code newStatus
 * == INACTIVE} (enforced by {@code ck_person_status_history_reason_
 * required_for_inactive}); {@code reasonDetail} is mandatory only when
 * {@code reasonCode == OTHER}.
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
    String reasonCode,
    String reasonDetail,
    UUID changedByAccountId,
    UUID correlationId,
    Instant changedAt) {}

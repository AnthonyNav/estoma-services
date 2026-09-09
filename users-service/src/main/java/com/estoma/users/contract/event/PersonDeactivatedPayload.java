package com.estoma.users.contract.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload of the {@code PersonDeactivated} event (events.md §5, §6).
 * {@code reasonCode} is always present; {@code reasonDetail} is present
 * only when {@code reasonCode == "OTHER"} (commands.md §7.1).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonDeactivatedPayload(
    UUID personId, String status, int version, Instant deactivatedAt, String reasonCode, String reasonDetail) {}

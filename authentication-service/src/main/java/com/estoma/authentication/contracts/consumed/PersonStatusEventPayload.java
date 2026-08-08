package com.estoma.authentication.contracts.consumed;

import java.util.UUID;

/**
 * Conceptual payload shared by {@code PersonCreated}/{@code
 * PersonActivated}/{@code PersonDeactivated} as consumed by this service:
 * only what {@code person_status_projection} needs (data-model.md §9).
 */
public record PersonStatusEventPayload(UUID personId, String status, int sourceVersion) {}

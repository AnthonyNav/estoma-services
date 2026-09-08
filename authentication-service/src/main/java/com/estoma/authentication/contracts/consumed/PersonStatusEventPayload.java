package com.estoma.authentication.contracts.consumed;

import java.util.UUID;

/**
 * Common fields in the canonical Users {@code PersonCreated}/{@code
 * PersonActivated}/{@code PersonDeactivated} payloads. Users calls its
 * aggregate version field {@code version}; the envelope repeats the same
 * value as {@code aggregateVersion}. Event validation requires them to match
 * before this payload reaches the projection handler.
 */
public record PersonStatusEventPayload(UUID personId, String status, int version) {}

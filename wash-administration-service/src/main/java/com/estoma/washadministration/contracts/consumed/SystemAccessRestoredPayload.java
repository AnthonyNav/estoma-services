package com.estoma.washadministration.contracts.consumed;

import java.time.Instant;
import java.util.UUID;

public record SystemAccessRestoredPayload(
    UUID systemAccessId,
    UUID accountId,
    UUID systemId,
    String systemCode,
    String previousStatus,
    String status,
    Instant restoredAt) {}

package com.estoma.washadministration.contracts.consumed;

import java.time.Instant;
import java.util.UUID;

public record SystemAccessSuspendedPayload(
    UUID systemAccessId,
    UUID accountId,
    UUID systemId,
    String systemCode,
    String previousStatus,
    String status,
    String reasonCode,
    String reasonDetail,
    Instant suspendedAt) {}

package com.estoma.washadministration.application.port;

import java.time.Instant;
import java.util.UUID;

/** Internal representation of an Authorization fact consumed by the projection updater. */
public record AuthorizationProjectionEvent(
    UUID messageId,
    String type,
    UUID accountId,
    String roleCode,
    String systemCode,
    String accessStatus,
    int sourceVersion,
    Instant occurredAt) {}

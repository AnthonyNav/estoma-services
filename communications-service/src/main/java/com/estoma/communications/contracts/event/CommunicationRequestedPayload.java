package com.estoma.communications.contracts.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code CommunicationRequested} (events.md §2, §4). */
public record CommunicationRequestedPayload(
    UUID communicationId,
    UUID recipientAccountId,
    String systemCode,
    String communicationType,
    String priority,
    List<String> requestedChannels,
    String status,
    Instant requestedAt) {}

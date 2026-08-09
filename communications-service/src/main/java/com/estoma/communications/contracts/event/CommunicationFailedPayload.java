package com.estoma.communications.contracts.event;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

/** {@code CommunicationFailed}: a required channel failed definitively (events.md §2). */
public record CommunicationFailedPayload(
    UUID communicationId, UUID recipientAccountId, String systemCode, String communicationType, List<String> failedChannels, Instant failedAt) {}

package com.estoma.communications.contracts.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code CommunicationCompleted}: every requested channel reached its terminal successful state (events.md §2). */
public record CommunicationCompletedPayload(
    UUID communicationId, UUID recipientAccountId, String systemCode, String communicationType, List<String> completedChannels, Instant completedAt) {}

package com.estoma.communications.contracts.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code InboxMessageCreated} -- a RESTRICTED integration event (events.md
 * §4): {@code title}/{@code body} travel here only to feed the BFF's
 * authorized {@code mailbox_messages_read_model} projection. Never log,
 * metric, or trace this payload's {@code title}/{@code body} fields.
 */
public record InboxMessageCreatedPayload(
    UUID inboxMessageId,
    UUID communicationId,
    UUID recipientAccountId,
    String systemCode,
    String communicationType,
    String title,
    String body,
    Instant createdAt,
    Instant expiresAt) {}

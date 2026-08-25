package com.estoma.platformbff.contracts.http.mailbox;

import java.time.Instant;
import java.util.UUID;

public record MailboxMessageResponse(
    UUID messageId,
    UUID communicationId,
    String systemCode,
    String communicationType,
    String title,
    String body,
    Instant createdAt,
    Instant expiresAt) {}

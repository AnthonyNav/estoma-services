package com.estoma.platformbff.contracts.http.mailbox;

import java.time.Instant;

public record MailboxSystemSummaryResponse(
    String systemCode,
    long messageCount,
    long newMessageCount,
    Instant lastVisitedAt) {}

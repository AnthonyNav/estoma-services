package com.estoma.platformbff.contracts.http.mailbox;

import java.time.Instant;
import com.estoma.platformbff.contracts.http.Required;

public record MailboxSystemSummaryResponse(
    @Required String systemCode,
    long messageCount,
    long newMessageCount,
    Instant lastVisitedAt) {}

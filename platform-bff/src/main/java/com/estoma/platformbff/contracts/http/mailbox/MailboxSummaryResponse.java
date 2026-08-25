package com.estoma.platformbff.contracts.http.mailbox;

import java.util.List;

public record MailboxSummaryResponse(
    long messageCount,
    long newMessageCount,
    List<MailboxSystemSummaryResponse> systems) {}

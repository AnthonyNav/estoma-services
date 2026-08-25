package com.estoma.platformbff.contracts.http.mailbox;

import java.util.List;

public record MailboxMessagesResponse(List<MailboxMessageResponse> messages) {}

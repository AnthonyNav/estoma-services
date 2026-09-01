package com.estoma.platformbff.contracts.http.mailbox;

import com.estoma.platformbff.contracts.http.RequiredNullable;
import com.estoma.platformbff.contracts.http.Required;
import java.util.List;

public record MailboxMessagesResponse(@Required List<MailboxMessageResponse> items,
                                      @RequiredNullable String nextCursor) {}

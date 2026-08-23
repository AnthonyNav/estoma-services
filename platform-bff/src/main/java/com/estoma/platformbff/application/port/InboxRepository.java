package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.messaging.InboxMessage;

public interface InboxRepository {
  boolean recordIfNew(InboxMessage message);
}

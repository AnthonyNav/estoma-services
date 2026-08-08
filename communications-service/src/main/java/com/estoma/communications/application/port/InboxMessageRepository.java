package com.estoma.communications.application.port;

import com.estoma.communications.domain.inbox.InboxMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InboxMessageRepository {

  void insert(InboxMessage message);

  /** One derived event per row this call transitions from ACTIVE to EXPIRED, for {@code InboxMessageExpired} (commands.md §2). */
  record ExpiredInboxMessage(UUID inboxMessageId, UUID communicationId) {}

  List<ExpiredInboxMessage> expireDue(Instant cutoff, int batchSize);
}

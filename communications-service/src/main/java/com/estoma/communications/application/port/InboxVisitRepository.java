package com.estoma.communications.application.port;

import java.time.Instant;
import java.util.UUID;

/** A delayed visit must not overwrite a more recent one -- same idiom as {@code RecipientProjectionRepository}'s source-version guard. */
public interface InboxVisitRepository {

  boolean upsertIfNewer(UUID accountId, String systemCode, Instant visitedAt, Instant now);
}

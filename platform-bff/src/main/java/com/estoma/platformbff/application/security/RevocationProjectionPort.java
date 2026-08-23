package com.estoma.platformbff.application.security;

import java.time.Instant;
import java.util.UUID;

public interface RevocationProjectionPort {
  void linkAccountToPerson(UUID accountId, UUID personId, long sourceVersion, Instant updatedAt);

  void revokeSession(UUID sessionId, long revokedAtEpochSeconds, String reasonCode, UUID sourceMessageId,
      Instant updatedAt);

  void revokeAccount(UUID accountId, long cutoff, String cutoffKind, UUID exceptSessionId,
      String reasonCode, UUID sourceMessageId, Instant updatedAt);

  void revokePerson(UUID personId, long cutoff, String reasonCode, UUID sourceMessageId, Instant updatedAt);
}

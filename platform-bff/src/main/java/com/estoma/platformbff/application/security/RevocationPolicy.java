package com.estoma.platformbff.application.security;

import java.util.UUID;

public final class RevocationPolicy {
  private RevocationPolicy() {}

  public static boolean rejects(
      UUID sessionId,
      long issuedAtEpochSeconds,
      boolean sessionRevoked,
      Long personCutoff,
      AccountCutoff accountCutoff) {
    if (sessionRevoked) return true;
    if (personCutoff != null && issuedAtEpochSeconds <= personCutoff) return true;
    return accountCutoff != null
        && issuedAtEpochSeconds <= accountCutoff.revokedBeforeOrAt()
        && (accountCutoff.all() || !sessionId.equals(accountCutoff.exceptSessionId()));
  }

  public record AccountCutoff(long revokedBeforeOrAt, boolean all, UUID exceptSessionId) {
  }
}

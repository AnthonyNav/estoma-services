package com.estoma.authentication.contracts.rpc;

import java.util.UUID;

/** Request for {@code ChangePassword} (commands.md §8.6), on behalf of the authenticated actor's own account. */
public record ChangePasswordRequest(UUID sessionId, String currentPassword, String newPassword) {
  @Deprecated
  public ChangePasswordRequest(UUID ignoredAccountId, UUID sessionId, String currentPassword, String newPassword) {
    this(sessionId, currentPassword, newPassword);
  }
}

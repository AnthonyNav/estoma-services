package com.estoma.communications.domain.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Last time an account visited its inbox for a given system
 * (data-model.md §5). A delayed visit must not overwrite a more recent
 * one -- enforced by {@code InboxVisitRepository.upsertIfNewer}, the same
 * idiom as {@code recipient_projection}'s source-version guard, not by
 * this class (it has no persisted "current" state to compare against).
 */
public final class InboxVisit {

  private final UUID accountId;
  private final String systemCode;
  private final Instant lastVisitedAt;
  private final Instant updatedAt;

  public InboxVisit(UUID accountId, String systemCode, Instant lastVisitedAt, Instant updatedAt) {
    this.accountId = requireNonNull(accountId, "accountId");
    this.systemCode = requireNonBlank(systemCode, "systemCode");
    this.lastVisitedAt = requireNonNull(lastVisitedAt, "lastVisitedAt");
    this.updatedAt = requireNonNull(updatedAt, "updatedAt");
  }

  public UUID accountId() {
    return accountId;
  }

  public String systemCode() {
    return systemCode;
  }

  public Instant lastVisitedAt() {
    return lastVisitedAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " must not be null");
    }
    return value;
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}

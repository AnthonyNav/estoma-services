package com.estoma.platformbff.application.security;

import java.time.Instant;
import java.util.UUID;

public sealed interface RevocationProjectionEvent
    permits RevocationProjectionEvent.AccountCreated,
        RevocationProjectionEvent.SessionRevoked,
        RevocationProjectionEvent.AccountSessionsRevoked,
        RevocationProjectionEvent.AccountDeactivated,
        RevocationProjectionEvent.PersonDeactivated {

  Metadata metadata();

  record Metadata(
      UUID messageId,
      String messageType,
      int messageVersion,
      String producer,
      Instant occurredAt,
      UUID correlationId,
      UUID causationId,
      UUID aggregateId,
      Integer aggregateVersion) {
  }

  record AccountCreated(
      Metadata metadata,
      UUID accountId,
      UUID personId,
      long sourceVersion,
      Instant effectiveAt) implements RevocationProjectionEvent {
  }

  record SessionRevoked(
      Metadata metadata,
      UUID sessionId,
      long revokedAtEpochSeconds,
      String reasonCode) implements RevocationProjectionEvent {
  }

  record AccountSessionsRevoked(
      Metadata metadata,
      UUID accountId,
      long revokedAtEpochSeconds,
      CutoffKind cutoffKind,
      UUID exceptSessionId,
      String reasonCode) implements RevocationProjectionEvent {
  }

  record AccountDeactivated(
      Metadata metadata,
      UUID accountId,
      UUID personId,
      long sourceVersion,
      long revokedAtEpochSeconds,
      String reasonCode,
      Instant effectiveAt) implements RevocationProjectionEvent {
  }

  record PersonDeactivated(
      Metadata metadata,
      UUID personId,
      long revokedAtEpochSeconds,
      String reasonCode) implements RevocationProjectionEvent {
  }

  enum CutoffKind { ALL, OTHER }
}

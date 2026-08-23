package com.estoma.communications.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Non-secret persistence boundary for password-recovery email orchestration. */
public interface PasswordRecoveryDeliveryPort {

  record RecoveryMetadata(UUID emailDeliveryId, UUID communicationId, UUID recoveryId, Instant expiresAt) {}

  record WaitingRecovery(
      UUID emailDeliveryId,
      UUID communicationId,
      UUID recipientAccountId,
      Instant expiresAt,
      String recipientAddress,
      String recipientName,
      String accountStatus,
      String personStatus) {}

  void insert(
      UUID emailDeliveryId,
      UUID communicationId,
      UUID recoveryId,
      Instant expiresAt,
      String recipientAddress,
      String recipientName,
      String fromName,
      String fromAddress,
      String senderCode,
      boolean waitingForRecipient,
      Instant now);

  Optional<RecoveryMetadata> findMetadata(UUID emailDeliveryId);

  List<WaitingRecovery> waiting(int limit);

  int resolveRecipient(UUID emailDeliveryId, String recipientAddress, String recipientName, Instant now);

  /** Moves an expired WAITING row to PENDING so the worker records the canonical pre-provider failure/events. */
  int releaseExpiredWaiting(UUID emailDeliveryId, Instant now);

  /** Technical RPC failure before any provider attempt: release the claim without changing attempt counters. */
  int requeueBeforeProviderAttempt(UUID emailDeliveryId, UUID claimToken, Instant nextAttemptAt, Instant now);
}

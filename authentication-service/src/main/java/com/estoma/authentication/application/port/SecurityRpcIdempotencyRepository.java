package com.estoma.authentication.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistence port for {@code security_rpc_idempotency_records}: protects
 * {@code SetTemporaryPassword}/{@code RegenerateTemporaryPassword} against a
 * repeated request generating or resending a secret (commands.md §7.1).
 * Not Consumer Inbox -- the ephemeral RPC path never uses Inbox, DLQ, or
 * {@code operationId}.
 */
public interface SecurityRpcIdempotencyRepository {

  /**
   * Reclaims {@code (operationType, idempotencyKey)} in one transactional step: inserts a new
   * {@code PROCESSING} row if the key is unseen, or inspects the existing row's {@code status} and
   * {@code request_fingerprint} if it is not.
   */
  ClaimResult tryClaim(
      String operationType, String idempotencyKey, UUID accountId, UUID requestedByAccountId, String requestFingerprint, Instant expiresAt);

  void markCompleted(String operationType, String idempotencyKey, Instant processedAt);

  void markFailed(String operationType, String idempotencyKey, String errorCode, Instant processedAt);

  sealed interface ClaimResult permits Claimed, AlreadyCompleted, AlreadyFailed, FingerprintMismatch {}

  /** This call may proceed: no prior record for this key, or it belongs to this exact request and is still {@code PROCESSING}. */
  record Claimed() implements ClaimResult {}

  /** {@code idempotencyKey} already completed (commands.md §7.1): the secret cannot be recovered or regenerated via this operation. */
  record AlreadyCompleted() implements ClaimResult {}

  /** {@code idempotencyKey} already failed with this sanitized functional code; repeating returns the same code. */
  record AlreadyFailed(String errorCode) implements ClaimResult {}

  /** Same {@code idempotencyKey}, different {@code requestFingerprint}: rejected without executing. */
  record FingerprintMismatch() implements ClaimResult {}
}

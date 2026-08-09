package com.estoma.authentication.application.port;

import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link PasswordRecoveryRequest}. */
public interface PasswordRecoveryRepository {

  Optional<PasswordRecoveryRequest> findById(UUID recoveryId);

  /** Locks a request while checking whether a privileged delivery read is still permitted. */
  Optional<PasswordRecoveryRequest> findByIdForUpdate(UUID recoveryId);

  /** Locks the sole active request for an account until the surrounding transaction completes. */
  Optional<PasswordRecoveryRequest> findActiveByAccountIdForUpdate(UUID accountId);

  void insert(PasswordRecoveryRequest request);

  /** Records a failed attempt and transitions the fifth one to {@code ATTEMPTS_EXHAUSTED}. */
  int recordFailedAttempt(UUID recoveryId, Instant now, int attemptLimit);

  /** Invalidates every currently active request for an account. */
  int invalidateActiveForAccount(UUID accountId, Instant now);

  /** Transitions an active expired request to {@code EXPIRED}. */
  int expireIfActive(UUID recoveryId, Instant now);

  /**
   * Atomic single-use consumption (data-model.md §12: "marcar como usada solo si no fue usada y no
   * expiró"): {@code UPDATE ... WHERE status = 'ACTIVE' AND expires_at > now}.
   *
   * @return 1 if this call consumed the request, 0 if it was already used or has expired -- the
   *     caller must re-read to disambiguate.
   */
  int markUsedIfValid(UUID recoveryId, Instant now, Instant usedAt);
}

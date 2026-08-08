package com.estoma.authentication.application.port;

import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link PasswordRecoveryRequest}. */
public interface PasswordRecoveryRepository {

  Optional<PasswordRecoveryRequest> findById(UUID recoveryId);

  void insert(PasswordRecoveryRequest request);

  /** Records a failed validation attempt, unconditional by {@code recoveryId}. */
  int update(PasswordRecoveryRequest request);

  /**
   * Atomic single-use consumption (data-model.md §12: "marcar como usada solo si no fue usada y no
   * expiró"): {@code UPDATE ... WHERE used_at IS NULL AND expires_at > now}.
   *
   * @return 1 if this call consumed the request, 0 if it was already used or has expired -- the
   *     caller must re-read to disambiguate.
   */
  int markUsedIfValid(UUID recoveryId, Instant now, Instant usedAt);
}

package com.estoma.authentication.application.port;

import com.estoma.authentication.domain.credential.Credential;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link Credential}, 0..1 with {@code Account}. {@link
 * #existsByAccountId} is what {@code ActivateAccount} checks before allowing
 * the transition (data-model.md §6).
 */
public interface CredentialRepository {

  Optional<Credential> findByAccountId(UUID accountId);

  boolean existsByAccountId(UUID accountId);

  /** First-time configuration; the row does not exist until this is called. */
  void insert(Credential credential);

  /** Rehash (ChangePassword/ResetPassword/RegenerateTemporaryPassword). No version column on this table. */
  int update(Credential credential);
}

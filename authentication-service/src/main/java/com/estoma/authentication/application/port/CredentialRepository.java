package com.estoma.authentication.application.port;

import com.estoma.authentication.domain.credential.Credential;
import java.util.Optional;
import java.time.Instant;
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

  /** Rehash or replace a temporary credential. */
  int update(Credential credential);

  /** Atomically consumes the exact temporary credential that was authenticated. */
  int consumeTemporaryIfValid(UUID accountId, int credentialVersion, Instant now);
}

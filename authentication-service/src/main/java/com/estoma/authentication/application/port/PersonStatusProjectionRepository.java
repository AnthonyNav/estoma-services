package com.estoma.authentication.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@code person_status_projection} -- this service's
 * own read model of whether a Person is globally active, not a domain
 * aggregate (data-model.md §9). Not the source of truth; no physical FK to
 * {@code users-db}.
 */
public interface PersonStatusProjectionRepository {

  Optional<PersonStatusProjectionRecord> findByPersonId(UUID personId);

  /**
   * Applies {@code PersonCreated}/{@code PersonActivated}/{@code
   * PersonDeactivated}, respecting {@code sourceVersion}
   * (events.md §9): {@code INSERT ... ON CONFLICT (person_id) DO UPDATE ...
   * WHERE source_version < EXCLUDED.source_version}.
   *
   * @return {@code true} if the row was inserted or updated; {@code false} if the event was stale
   *     (an equal or lower {@code sourceVersion} than what is already stored) and was ignored.
   */
  boolean upsertIfNewer(UUID personId, String status, int sourceVersion, Instant updatedAt);

  record PersonStatusProjectionRecord(UUID personId, String status, int sourceVersion, Instant updatedAt) {}
}

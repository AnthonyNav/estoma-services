package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.ProjectedStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PersonStatusProjectionRepository {

  Optional<PersonStatusProjection> findByPersonId(UUID personId);

  int upsertIfNewer(PersonStatusProjection projection);

  record PersonStatusProjection(UUID personId, ProjectedStatus status, int sourceVersion, Instant updatedAt) {}
}

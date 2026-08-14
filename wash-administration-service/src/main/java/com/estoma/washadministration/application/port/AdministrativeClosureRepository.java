package com.estoma.washadministration.application.port;

import com.estoma.washadministration.domain.closure.AdministrativeClosure;
import com.estoma.washadministration.domain.closure.AdministrativeClosureScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdministrativeClosureRepository {

  Optional<AdministrativeClosure> findById(UUID id);

  Optional<AdministrativeClosure> findByIdForUpdate(UUID id);

  List<AdministrativeClosure> findAll();

  Optional<AdministrativeClosure> findExact(
      UUID serviceCalendarId,
      AdministrativeClosureScope scope,
      UUID cabinId,
      UUID tankId,
      Instant startsAt,
      Instant endsAt,
      String reason);

  void insert(AdministrativeClosure closure);

  int update(AdministrativeClosure closure, int expectedVersion);
}

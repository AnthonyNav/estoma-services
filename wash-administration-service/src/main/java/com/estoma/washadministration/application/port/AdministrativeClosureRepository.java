package com.estoma.washadministration.application.port;

import com.estoma.washadministration.domain.closure.AdministrativeClosure;
import java.util.Optional;
import java.util.UUID;

public interface AdministrativeClosureRepository {

  Optional<AdministrativeClosure> findById(UUID id);

  void insert(AdministrativeClosure closure);

  int update(AdministrativeClosure closure, int expectedVersion);
}

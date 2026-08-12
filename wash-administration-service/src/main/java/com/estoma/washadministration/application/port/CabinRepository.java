package com.estoma.washadministration.application.port;

import com.estoma.washadministration.domain.resource.Cabin;
import java.util.Optional;
import java.util.UUID;

public interface CabinRepository {

  Optional<Cabin> findById(UUID id);

  Optional<Cabin> findByCode(String code);

  void insert(Cabin cabin);

  int update(Cabin cabin, int expectedVersion);
}

package com.estoma.washadministration.application.port;

import com.estoma.washadministration.domain.resource.Cabin;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CabinRepository {

  Optional<Cabin> findById(UUID id);

  List<Cabin> findAll();

  /**
   * Loads and locks the parent cabin for cross-aggregate operations that must serialize with tank
   * registration and cabin retirement.
   */
  Optional<Cabin> findByIdForUpdate(UUID id);

  Optional<Cabin> findByCode(String code);

  void insert(Cabin cabin);

  int update(Cabin cabin, int expectedVersion);
}

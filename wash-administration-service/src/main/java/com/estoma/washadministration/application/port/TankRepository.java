package com.estoma.washadministration.application.port;

import com.estoma.washadministration.domain.resource.Tank;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TankRepository {

  Optional<Tank> findById(UUID id);

  Optional<Tank> findByIdForUpdate(UUID id);

  List<Tank> findAll();

  Optional<Tank> findByCabinIdAndCode(UUID cabinId, String code);

  long countByCabinId(UUID cabinId);

  long countNonRetiredByCabinId(UUID cabinId);

  void insert(Tank tank);

  int update(Tank tank, int expectedVersion);
}

package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.RoleCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleCatalogRepository {

  Optional<RoleCatalog> findById(UUID roleId);

  Optional<RoleCatalog> findByCode(String code);

  List<RoleCatalog> findAll();
}

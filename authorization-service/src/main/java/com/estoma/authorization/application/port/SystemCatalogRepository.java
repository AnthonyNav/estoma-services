package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.SystemCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SystemCatalogRepository {

  Optional<SystemCatalog> findById(UUID systemId);

  Optional<SystemCatalog> findByCode(String code);

  List<SystemCatalog> findAll();
}

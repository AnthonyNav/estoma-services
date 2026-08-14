package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.PermissionCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionCatalogRepository {

  Optional<PermissionCatalog> findById(UUID permissionId);

  Optional<PermissionCatalog> findBySystemIdAndCode(UUID systemId, String code);

  List<PermissionCatalog> findByRoleId(UUID roleId);

  List<PermissionCatalog> findAll();
}

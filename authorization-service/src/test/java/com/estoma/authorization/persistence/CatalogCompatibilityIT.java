package com.estoma.authorization.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authorization.application.port.RoleCatalogRepository;
import com.estoma.authorization.application.port.SystemCatalogRepository;
import com.estoma.authorization.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CatalogCompatibilityIT extends AbstractPostgresIT {

  @Autowired private RoleCatalogRepository roles;
  @Autowired private SystemCatalogRepository systems;

  @Test
  void roleSystemCompatibilityComesFromThePersistedV1Matrix() {
    var alumno = roles.findByCode("ALUMNO").orElseThrow();
    var supervisor = roles.findByCode("SUPERVISOR_LAVADO").orElseThrow();
    var practicesAdmin = roles.findByCode("ADMINISTRADOR_PRACTICAS").orElseThrow();
    var superadmin = roles.findByCode("SUPERADMIN").orElseThrow();
    var lavado = systems.findByCode("LAVADO_ULTRASONICO").orElseThrow();
    var practices = systems.findByCode("PRACTICAS_PROFESIONALES").orElseThrow();

    assertThat(roles.isAllowedInSystem(alumno.id(), lavado.id())).isTrue();
    assertThat(roles.isAllowedInSystem(alumno.id(), practices.id())).isTrue();

    assertThat(roles.isAllowedInSystem(supervisor.id(), lavado.id())).isTrue();
    assertThat(roles.isAllowedInSystem(supervisor.id(), practices.id())).isFalse();

    assertThat(roles.isAllowedInSystem(practicesAdmin.id(), lavado.id())).isFalse();
    assertThat(roles.isAllowedInSystem(practicesAdmin.id(), practices.id())).isTrue();

    assertThat(roles.isAllowedInSystem(superadmin.id(), lavado.id())).isTrue();
    assertThat(roles.isAllowedInSystem(superadmin.id(), practices.id())).isTrue();
  }
}

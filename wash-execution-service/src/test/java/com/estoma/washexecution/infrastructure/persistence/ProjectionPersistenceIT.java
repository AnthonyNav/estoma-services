package com.estoma.washexecution.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProjectionPersistenceIT extends AbstractPostgresIT {
  @Autowired ProjectionRepositories.AdministrativeClosureRepository closures;
  @Autowired ProjectionRepositories.AuthorizationProjectionRepository authorization;

  @Test
  void scheduledClosureBlocksAndCancelledClosureDoesNot() {
    UUID calendarId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    Instant start = Instant.parse("2026-08-23T10:00:00Z");
    Instant end = start.plusSeconds(3600);
    UUID closureId = UUID.randomUUID();

    closures.upsert(new ProjectionRepositories.AdministrativeClosureProjection(
        closureId, calendarId, "CABIN", cabinId, null, start, end, "SCHEDULED", 1));
    assertThat(closures.findOverlapping(cabinId, null, start.plusSeconds(60), end.minusSeconds(60)))
        .extracting(ProjectionRepositories.AdministrativeClosureProjection::administrativeClosureId)
        .containsExactly(closureId);

    closures.upsert(new ProjectionRepositories.AdministrativeClosureProjection(
        closureId, calendarId, "CABIN", cabinId, null, start, end, "CANCELLED", 2));
    assertThat(closures.findOverlapping(cabinId, null, start.plusSeconds(60), end.minusSeconds(60)))
        .isEmpty();
  }

  @Test
  void authorizationRoleAndAccessAdvanceIndependentlyAndIgnoreStaleSnapshots() {
    UUID accountId = UUID.randomUUID();
    UUID systemId = UUID.randomUUID();

    authorization.upsertRole(new ProjectionRepositories.RoleProjection(accountId, "SUPERVISOR_LAVADO", 5));
    authorization.upsertAccess(new ProjectionRepositories.AccessProjection(
        accountId, systemId, "LAVADO_ULTRASONICO", "ACTIVE", 8));

    authorization.upsertRole(new ProjectionRepositories.RoleProjection(accountId, "ALUMNO", 4));
    authorization.upsertAccess(new ProjectionRepositories.AccessProjection(
        accountId, systemId, "LAVADO_ULTRASONICO", "SUSPENDED", 7));

    assertThat(authorization.role(accountId).orElseThrow().roleCode()).isEqualTo("SUPERVISOR_LAVADO");
    assertThat(authorization.role(accountId).orElseThrow().roleSourceVersion()).isEqualTo(5);
    assertThat(authorization.access(accountId, "LAVADO_ULTRASONICO").orElseThrow().accessStatus())
        .isEqualTo("ACTIVE");
    assertThat(authorization.access(accountId, "LAVADO_ULTRASONICO").orElseThrow().accessSourceVersion())
        .isEqualTo(8);
  }
}

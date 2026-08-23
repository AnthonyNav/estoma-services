package com.estoma.washexecution.application.authorization;

import com.estoma.washexecution.application.command.ActorContext;
import com.estoma.washexecution.application.command.CommandRejectedException;
import com.estoma.washexecution.application.port.ProjectionRepositories;
import java.util.UUID;

public final class AuthorizationEvaluator {
  private static final String SYSTEM_CODE = "LAVADO_ULTRASONICO";
  private static final String SUPERVISOR_ROLE = "SUPERVISOR_LAVADO";
  private static final String STUDENT_ROLE = "ALUMNO";

  private final ProjectionRepositories.AuthorizationProjectionRepository projections;
  private final ProjectionReadinessGuard readiness;

  public AuthorizationEvaluator(
      ProjectionRepositories.AuthorizationProjectionRepository projections,
      ProjectionReadinessGuard readiness) {
    this.projections = projections;
    this.readiness = readiness;
  }

  public UUID requireSupervisor(ActorContext actor) {
    UUID accountId = requireAccount(actor);
    readiness.requireReady(ProjectionReadinessGuard.AUTHORIZATION, "ACTOR_NOT_AUTHORIZED");
    boolean roleAllowed = projections.role(accountId)
        .map(ProjectionRepositories.RoleProjection::roleCode)
        .filter(SUPERVISOR_ROLE::equals)
        .isPresent();
    boolean accessAllowed = projections.access(accountId, SYSTEM_CODE)
        .map(ProjectionRepositories.AccessProjection::accessStatus)
        .filter("ACTIVE"::equals)
        .isPresent();
    if (!roleAllowed || !accessAllowed) {
      throw new CommandRejectedException("ACTOR_NOT_AUTHORIZED");
    }
    return accountId;
  }

  public UUID requireStudentOwner(ActorContext actor, UUID expectedStudentAccountId) {
    UUID accountId = requireAccount(actor);
    readiness.requireReady(ProjectionReadinessGuard.AUTHORIZATION, "ACTOR_NOT_AUTHORIZED");
    boolean roleAllowed = projections.role(accountId)
        .map(ProjectionRepositories.RoleProjection::roleCode)
        .filter(STUDENT_ROLE::equals)
        .isPresent();
    boolean accessAllowed = projections.access(accountId, SYSTEM_CODE)
        .map(ProjectionRepositories.AccessProjection::accessStatus)
        .filter("ACTIVE"::equals)
        .isPresent();
    if (!roleAllowed || !accessAllowed || !accountId.equals(expectedStudentAccountId)) {
      throw new CommandRejectedException("ACTOR_NOT_AUTHORIZED");
    }
    return accountId;
  }

  private static UUID requireAccount(ActorContext actor) {
    if (actor == null || !actor.isAccount()) {
      throw new CommandRejectedException("ACTOR_NOT_AUTHORIZED");
    }
    return actor.accountId();
  }
}

package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import java.util.Set;
import java.util.UUID;

/** Fail-closed evaluator using only canonical local Authorization projections. */
final class LocalAuthorizationPolicy {
  private static final String WASH = "LAVADO_ULTRASONICO";
  private final AuthorizationProjectionRepository authorization;

  LocalAuthorizationPolicy(AuthorizationProjectionRepository authorization) {
    this.authorization = authorization;
  }

  boolean canSchedule(CommandMetadata metadata, UUID studentId) {
    ActorContext actor = metadata.actor();
    return actor.isAccount() && actor.accountId().equals(studentId)
        && canStudentSchedule(studentId);
  }

  /** Evaluates the target student's owner-local authorization without impersonating a SYSTEM actor. */
  boolean canStudentSchedule(UUID studentId) {
    return studentId != null && hasRoleAndAccess(studentId, Set.of("ALUMNO"));
  }

  boolean canCancelOwn(CommandMetadata metadata, UUID studentId) {
    ActorContext actor = metadata.actor();
    return actor.isAccount() && actor.accountId().equals(studentId)
        && hasRoleAndAccess(actor.accountId(), Set.of("ALUMNO"));
  }

  boolean canGrantExceptionalAuthorization(CommandMetadata metadata) {
    ActorContext actor = metadata.actor();
    return actor.isAccount() && hasRoleAndAccess(actor.accountId(), Set.of("SUPERVISOR_LAVADO"));
  }

  boolean canAdministerSlots(CommandMetadata metadata) {
    ActorContext actor = metadata.actor();
    return actor.isAccount() && hasRoleAndAccess(actor.accountId(), Set.of("ADMINISTRADOR_LAVADO"));
  }

  boolean isInternalAppointmentsProcess(CommandMetadata metadata) {
    ActorContext actor = metadata.actor();
    return actor.isSystem() && "appointments".equals(actor.service());
  }

  private boolean hasRoleAndAccess(UUID accountId, Set<String> roles) {
    if (!authorization.reliable()) return false;
    var role = authorization.findAccountRole(accountId).orElse(null);
    var access = authorization.findSystemAccess(accountId, WASH).orElse(null);
    return role != null && role.reliable() && roles.contains(role.roleCode())
        && access != null && access.reliable() && "ACTIVE".equals(access.accessStatus());
  }
}

package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import java.util.UUID;

/** Fail-closed local authorization evaluator. */
final class LocalAuthorizationPolicy {
  private final AuthorizationProjectionRepository authorization;

  LocalAuthorizationPolicy(AuthorizationProjectionRepository authorization) {
    this.authorization = authorization;
  }

  boolean canSchedule(CommandMetadata metadata, UUID studentId) {
    ActorContext actor = metadata.actor();
    return actor.accountId().equals(studentId)
        && actor.isStudent()
        && hasReliableAccess(actor);
  }

  boolean canManage(CommandMetadata metadata) {
    ActorContext actor = metadata.actor();
    return !actor.isStudent() && hasReliableAccess(actor);
  }

  boolean canCancelOwn(CommandMetadata metadata, UUID studentId) {
    return metadata.actor().accountId().equals(studentId)
        && metadata.actor().isStudent()
        && hasReliableAccess(metadata.actor());
  }

  private boolean hasReliableAccess(ActorContext actor) {
    return authorization.reliable()
        && authorization.hasAccess(actor.accountId(), actor.roleCode(), actor.systemCode());
  }
}

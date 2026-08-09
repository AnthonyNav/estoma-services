package com.estoma.authentication.application;

import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import org.springframework.stereotype.Component;

/**
 * Enforces Authentication's local authorization boundary. Functional role and
 * system permission evaluation remains upstream; this service never accepts
 * an anonymous, malformed or scope-less administrative actor as equivalent to
 * an authorized one.
 */
@Component
class ContractAccountAuthorizationPolicy implements AccountAuthorizationPolicy {

  @Override
  public void check(Actor actor, String operation) {
    if (actor == null || actor.type() == null) {
      reject(operation);
    }
    if ("ACCOUNT".equals(actor.type()) && actor.accountId() != null) {
      return;
    }
    if ("SYSTEM".equals(actor.type()) && actor.service() != null && !actor.service().isBlank()) {
      return;
    }
    reject(operation);
  }

  private static void reject(String operation) {
    throw new AuthenticationRejectedException(RejectionCode.ACTOR_NOT_AUTHORIZED, "actor is not contractually valid for " + operation);
  }
}

package com.estoma.authentication.application;

import com.estoma.authentication.contracts.Actor;
import org.springframework.stereotype.Component;

/**
 * Interim {@link AccountAuthorizationPolicy}: a no-op, since {@code
 * authorization-service} does not exist yet (system_contract.md
 * "Prohibited Behavior": this service does not administer roles or
 * permissions itself). The actor is still recorded via structured logs
 * regardless -- this policy only decides whether the operation may
 * proceed, and for now it always may.
 */
@Component
class AllowAllAccountAuthorizationPolicy implements AccountAuthorizationPolicy {

  @Override
  public void check(Actor actor, String operation) {
    // Intentionally empty. See class Javadoc.
  }
}

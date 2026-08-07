package com.estoma.users.application;

import com.estoma.users.contract.Actor;
import org.springframework.stereotype.Component;

/**
 * Interim {@link PersonAuthorizationPolicy}: a no-op, since {@code
 * authorization-service} does not exist yet. The actor is recorded in
 * {@code person_status_history.changed_by_account_id} and in audit logs
 * regardless (docs/system-contract.md §12) — this policy only decides
 * whether the operation may proceed, and for now it always may.
 */
@Component
class AllowAllPersonAuthorizationPolicy implements PersonAuthorizationPolicy {

  @Override
  public void check(Actor actor, String operation) {
    // Intentionally empty. See class Javadoc and
    // docs/system-contract.md §10 (D5).
  }
}

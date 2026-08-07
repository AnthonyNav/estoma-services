package com.estoma.users.application;

import com.estoma.users.contract.Actor;

/**
 * Domain-level authorization seam (docs/system-contract.md §10 D5, §12):
 * {@code authorization-service} does not exist yet, and none of the four
 * commands this service handles has a rejection code for an authorization
 * failure — inventing one (e.g. {@code FORBIDDEN}) would break the
 * platform contract. This exists so real cross-service authorization can
 * be plugged in later without changing {@link PersonCommandDispatcher}'s
 * call shape.
 *
 * <p>{@code EnvelopeValidator} already guarantees {@code actor} and
 * {@code actor.accountId} are present by the time this runs — it is a
 * structural precondition of every command, not a business rule this
 * policy needs to re-check.
 */
public interface PersonAuthorizationPolicy {

  void check(Actor actor, String operation);
}

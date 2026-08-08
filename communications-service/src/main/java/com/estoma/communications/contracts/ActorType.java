package com.estoma.communications.contracts;

/**
 * The two shapes an {@link Actor} may take (messaging-architecture.md
 * §8.1). {@code ACCOUNT} carries {@code accountId}; {@code SYSTEM} carries
 * {@code service}. Unlike {@code authentication-service}'s {@code Actor}
 * (which is always an account, since every operation it receives is
 * initiated by a logged-in account or the BFF on its behalf), this service
 * also receives commands issued by other microservices and by its own
 * in-process adapters (system_contract.md "Internal Architecture"), so both
 * shapes are needed from the start.
 */
public enum ActorType {
  ACCOUNT,
  SYSTEM
}

package com.estoma.users.domain;

/**
 * The exact, closed set of functional rejection codes this service's
 * commands may produce. No other code may be introduced without updating
 * this enum and docs/system-contract.md §3.
 *
 * <p>Deliberately does NOT include an authorization-failure code: none of
 * the four commands has one in the platform docs, and {@code
 * authorization-service} does not exist yet. See docs/system-contract.md
 * §10 (D5) and §12.
 */
public enum RejectionCode {
  PERSON_ALREADY_EXISTS,
  INVALID_PERSON_DATA,
  DUPLICATE_PERSON,
  PERSON_NOT_FOUND,
  PERSON_VERSION_CONFLICT,
  PERSON_ALREADY_ACTIVE,
  PERSON_ALREADY_INACTIVE,

  /**
   * The envelope's {@code aggregateId} and the payload's {@code personId}
   * are both present and differ (commands.md §2.1, §3). A controlled
   * functional rejection — never a DLQ'd message — checked once for all
   * four commands in {@code PersonCommandDispatcher}, not per-handler.
   */
  INVALID_MESSAGE
}

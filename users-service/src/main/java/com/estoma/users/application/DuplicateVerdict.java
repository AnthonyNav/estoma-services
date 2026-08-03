package com.estoma.users.application;

import java.util.UUID;

/**
 * A verdict, not a boolean, so a future fuzzier duplicate rule (matching
 * on name + birthdate, say) can carry richer information without
 * changing any caller. See docs/system-contract.md §6.
 */
public sealed interface DuplicateVerdict {

  record None() implements DuplicateVerdict {}

  record Duplicate(UUID matchedPersonId, String matchedOn) implements DuplicateVerdict {}
}

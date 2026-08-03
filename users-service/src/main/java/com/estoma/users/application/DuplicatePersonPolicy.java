package com.estoma.users.application;

import java.util.UUID;

/**
 * The interim {@code DUPLICATE_PERSON} rule (docs/system-contract.md §6):
 * one institutional mailbox belongs to exactly one Person. The real
 * arbiter is the database's {@code ux_persons_email_normalized} unique
 * index; this policy exists to produce a clean rejection *before* hitting
 * that constraint, for the common case.
 */
public interface DuplicatePersonPolicy {

  /**
   * @param excludingPersonId when checking an {@code UpdatePerson}, the
   *     person being updated — a match against its own current email is
   *     not a duplicate. {@code null} for {@code CreatePerson}.
   */
  DuplicateVerdict check(String normalizedEmail, UUID excludingPersonId);
}

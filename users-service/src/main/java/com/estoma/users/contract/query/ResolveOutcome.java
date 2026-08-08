package com.estoma.users.contract.query;

/**
 * The three mutually-exclusive outcomes of {@code
 * ResolvePersonByInstitutionalEmail} (queries.md §2) — mutually exclusive
 * by construction, since {@code institutional_email_normalized} is
 * globally unique across every Person regardless of status.
 */
public enum ResolveOutcome {
  /** An {@code ACTIVE} Person matches. */
  FOUND,

  /** An {@code INACTIVE} Person matches — the caller must not auto-reuse or auto-reactivate it. */
  INACTIVE_MATCH,

  /** No Person, active or inactive, matches. */
  NOT_FOUND
}

package com.estoma.users.domain;

/**
 * The closed catalog of {@code DeactivatePerson} {@code reasonCode}
 * values (commands.md §7.1) — intentionally small and not meant to grow
 * casually. Persisted as {@code varchar(40)}, not a Postgres enum
 * (data-model.md §5 fixes the column type; the constraint that values
 * belong to this catalog lives in the migration's {@code CHECK}, not in
 * the schema type itself).
 */
public enum DeactivationReason {
  DUPLICATE,
  CREATED_BY_ERROR,
  IDENTITY_CORRECTION,
  ADMINISTRATIVE_REQUEST,
  OTHER
}

package com.estoma.users.persistence;

/**
 * Lifecycle of one {@code outbox_messages} row. Mirrors the {@code
 * outbox_status} Postgres enum (V1__enums.sql).
 *
 * <p>Not a domain concept — the Transactional Outbox is plumbing, per
 * data-model.md §2 ("no es entidad de dominio"), hence this lives in
 * {@code persistence}, not {@code domain}.
 */
public enum OutboxStatus {
  PENDING,
  PROCESSING,
  PUBLISHED,
  FAILED
}

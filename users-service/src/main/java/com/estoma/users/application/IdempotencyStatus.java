package com.estoma.users.application;

/** Mirrors the {@code idempotency_status} Postgres enum (V7__idempotency_records.sql). */
public enum IdempotencyStatus {
  IN_PROGRESS,
  SUCCEEDED,
  REJECTED
}

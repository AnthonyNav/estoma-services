package com.estoma.users.contract.result;

/**
 * The terminal status of a Command or Query Result
 * (messaging-architecture.md §3 "Resultado"). All four are terminal —
 * technical retries happen during Command processing, before a
 * definitive Result is produced.
 */
public enum ResultStatus {
  SUCCEEDED,
  REJECTED,
  FAILED,
  EXPIRED
}

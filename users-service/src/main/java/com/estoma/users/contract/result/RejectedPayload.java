package com.estoma.users.contract.result;

import java.util.List;

/**
 * Shared payload shape for all four {@code <Command>Rejected} results.
 * The shape is an interim, cross-service decision — commands.md:321 lists
 * it as pending platform-wide — recorded here per
 * docs/system-contract.md §4, §10 (D4).
 */
public record RejectedPayload(String errorCode, String message, List<RejectionDetail> details) {

  public RejectedPayload {
    details = details == null ? List.of() : List.copyOf(details);
  }

  public static RejectedPayload of(String errorCode, String message) {
    return new RejectedPayload(errorCode, message, List.of());
  }
}

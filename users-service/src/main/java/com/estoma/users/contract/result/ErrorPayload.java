package com.estoma.users.contract.result;

import java.util.Map;

/**
 * The {@code error} object of a {@code REJECTED}/{@code FAILED}/{@code
 * EXPIRED} Result (messaging-architecture.md §3 "Resultado"): {@code
 * code} is mandatory, {@code details} optional. There is no {@code
 * message} field on the wire — a free-text explanation is not part of
 * the platform's minimal Result shape; the equivalent detail is logged
 * instead (see {@code PersonCommandDispatcher}).
 */
public record ErrorPayload(String code, Map<String, Object> details) {

  public ErrorPayload {
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  public static ErrorPayload of(String code) {
    return new ErrorPayload(code, Map.of());
  }
}

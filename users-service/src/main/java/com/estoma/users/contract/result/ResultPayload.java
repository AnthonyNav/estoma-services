package com.estoma.users.contract.result;

/**
 * The platform's minimal common shape for every RESULT message's {@code
 * payload} (messaging-architecture.md §3 "Resultado"): {@code status},
 * {@code data?}, {@code error?}. The platform defines only this shape;
 * each microservice defines the content of {@code data} and its own
 * {@code error.code} catalog. Here {@code data} is a {@link
 * SucceededPayload} for {@code SUCCEEDED}, {@code null} otherwise; {@code
 * error} is an {@link ErrorPayload} for {@code REJECTED}, {@code null}
 * otherwise — {@code users-service} produces no {@code FAILED}/{@code
 * EXPIRED} Result today.
 */
public record ResultPayload(ResultStatus status, Object data, ErrorPayload error) {

  public static ResultPayload succeeded(Object data) {
    return new ResultPayload(ResultStatus.SUCCEEDED, data, null);
  }

  public static ResultPayload rejected(String errorCode) {
    return new ResultPayload(ResultStatus.REJECTED, null, ErrorPayload.of(errorCode));
  }
}

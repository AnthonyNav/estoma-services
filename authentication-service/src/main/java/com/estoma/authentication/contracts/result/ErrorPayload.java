package com.estoma.authentication.contracts.result;

/** A service-defined error code in the common V1 Result payload. */
public record ErrorPayload(String code) {

  public static ErrorPayload of(String code) {
    return new ErrorPayload(code);
  }
}

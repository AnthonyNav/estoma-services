package com.estoma.authentication.contracts.result;

/** {@code <Command>Rejected} payload. Never includes a secret, password, hash, or token. */
public record RejectedPayload(String errorCode, String message) {

  public static RejectedPayload of(String errorCode, String message) {
    return new RejectedPayload(errorCode, message);
  }
}

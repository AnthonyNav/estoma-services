package com.estoma.communications.contracts.result;

/** {@code <Command>Rejected} payload, same shape as authentication-service's. Never includes a secret. */
public record RejectedPayload(String errorCode, String message) {

  public static RejectedPayload of(String errorCode, String message) {
    return new RejectedPayload(errorCode, message);
  }
}

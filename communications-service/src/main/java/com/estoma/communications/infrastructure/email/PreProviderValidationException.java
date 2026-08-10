package com.estoma.communications.infrastructure.email;

/** A deterministic failure detected before an email provider attempt exists. */
final class PreProviderValidationException extends RuntimeException {
  private final String normalizedErrorCode;
  private final String sanitizedMessage;

  PreProviderValidationException(String normalizedErrorCode, String sanitizedMessage) {
    super(sanitizedMessage);
    this.normalizedErrorCode = normalizedErrorCode;
    this.sanitizedMessage = sanitizedMessage;
  }

  String normalizedErrorCode() {
    return normalizedErrorCode;
  }

  String sanitizedMessage() {
    return sanitizedMessage;
  }
}

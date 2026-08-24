package com.estoma.communications.application.port;

import java.util.UUID;

/** Ephemeral Security RPC boundary. Returned codes may exist only in memory for the immediate provider call. */
public interface RecoveryCodeDeliveryPort {

  String getCode(UUID recoveryId, UUID correlationId);

  final class NotAvailableException extends RuntimeException {
    private final String normalizedCode;

    public NotAvailableException(String normalizedCode) {
      super(normalizedCode);
      this.normalizedCode = normalizedCode;
    }

    public String normalizedCode() {
      return normalizedCode;
    }
  }

  final class TechnicalException extends RuntimeException {
    public TechnicalException(String message) {
      super(message);
    }

    public TechnicalException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

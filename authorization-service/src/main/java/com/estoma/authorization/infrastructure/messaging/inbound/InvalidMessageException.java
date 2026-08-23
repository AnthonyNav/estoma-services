package com.estoma.authorization.infrastructure.messaging.inbound;

final class InvalidMessageException extends RuntimeException {

  InvalidMessageException(String message) {
    super(message);
  }

  InvalidMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}

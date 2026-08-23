package com.estoma.platformbff.application.security;

public class SecurityRpcUnavailableException extends RuntimeException {
  public SecurityRpcUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  public SecurityRpcUnavailableException(String message) {
    super(message);
  }
}

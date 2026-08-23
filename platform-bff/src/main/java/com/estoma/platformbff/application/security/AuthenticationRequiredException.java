package com.estoma.platformbff.application.security;

public class AuthenticationRequiredException extends RuntimeException {
  public AuthenticationRequiredException() {
    super("authentication is required");
  }
}

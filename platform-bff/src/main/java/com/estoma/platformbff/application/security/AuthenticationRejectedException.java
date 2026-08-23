package com.estoma.platformbff.application.security;

public class AuthenticationRejectedException extends RuntimeException {
  public AuthenticationRejectedException() {
    super("authentication request was rejected");
  }
}

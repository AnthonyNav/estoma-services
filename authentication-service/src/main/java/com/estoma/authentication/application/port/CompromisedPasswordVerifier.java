package com.estoma.authentication.application.port;

/** Checks a candidate password against a locally available compromised-password source. */
public interface CompromisedPasswordVerifier {

  boolean isCompromised(String password);
}

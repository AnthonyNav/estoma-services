package com.estoma.authentication.application.rpc;

import com.estoma.authentication.application.port.CompromisedPasswordVerifier;
import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.credential.PasswordHash;
import org.springframework.stereotype.Component;

/** The shared policy applied before any persisted password change. */
@Component
public class PasswordPolicy {

  static final int MINIMUM_LENGTH = 15;
  static final int MAXIMUM_LENGTH = 128;

  private final PasswordHasher passwordHasher;
  private final CompromisedPasswordVerifier compromisedPasswordVerifier;

  PasswordPolicy(PasswordHasher passwordHasher, CompromisedPasswordVerifier compromisedPasswordVerifier) {
    this.passwordHasher = passwordHasher;
    this.compromisedPasswordVerifier = compromisedPasswordVerifier;
  }

  public void validate(String password, PasswordHash currentHash) {
    if (password == null || password.length() < MINIMUM_LENGTH || password.length() > MAXIMUM_LENGTH) {
      throw new AuthenticationRejectedException(
          RejectionCode.INVALID_PASSWORD,
          "password must be between " + MINIMUM_LENGTH + " and " + MAXIMUM_LENGTH + " characters");
    }
    if (compromisedPasswordVerifier.isCompromised(password)) {
      throw new AuthenticationRejectedException(RejectionCode.INVALID_PASSWORD, "password is commonly compromised");
    }
    if (currentHash != null && passwordHasher.matches(password, currentHash)) {
      throw new AuthenticationRejectedException(
          RejectionCode.PASSWORD_REUSE, "new password must differ from the current password");
    }
  }
}

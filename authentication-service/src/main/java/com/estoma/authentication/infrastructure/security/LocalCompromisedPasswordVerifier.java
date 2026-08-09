package com.estoma.authentication.infrastructure.security;

import com.estoma.authentication.application.port.CompromisedPasswordVerifier;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Small local deny-list; password validation never makes an outbound request. */
@Component
public class LocalCompromisedPasswordVerifier implements CompromisedPasswordVerifier {

  private static final Set<String> COMMON_PASSWORDS = Set.of(
      "password", "password123", "passwordpassword", "123456789", "1234567890", "qwertyuiop",
      "letmein", "welcome", "admin", "iloveyou", "monkey", "dragon");

  @Override
  public boolean isCompromised(String password) {
    return password != null && COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT));
  }
}

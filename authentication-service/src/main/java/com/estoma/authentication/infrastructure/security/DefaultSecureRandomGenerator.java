package com.estoma.authentication.infrastructure.security;

import com.estoma.authentication.application.port.SecureRandomGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** {@link java.security.SecureRandom}-backed implementation of {@link SecureRandomGenerator}. */
@Component
class DefaultSecureRandomGenerator implements SecureRandomGenerator {

  private static final String TEMPORARY_PASSWORD_ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*";
  private static final int TEMPORARY_PASSWORD_LENGTH = 12;

  private final SecureRandom random = new SecureRandom();

  @Override
  public String randomNumericCode(int digits) {
    StringBuilder code = new StringBuilder(digits);
    for (int i = 0; i < digits; i++) {
      code.append(random.nextInt(10));
    }
    return code.toString();
  }

  @Override
  public String randomUrlSafeToken(int byteLength) {
    byte[] bytes = new byte[byteLength];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  @Override
  public String randomTemporaryPassword() {
    StringBuilder password = new StringBuilder(TEMPORARY_PASSWORD_LENGTH);
    for (int i = 0; i < TEMPORARY_PASSWORD_LENGTH; i++) {
      password.append(TEMPORARY_PASSWORD_ALPHABET.charAt(random.nextInt(TEMPORARY_PASSWORD_ALPHABET.length())));
    }
    return password.toString();
  }
}

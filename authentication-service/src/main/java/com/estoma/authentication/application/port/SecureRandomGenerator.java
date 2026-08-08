package com.estoma.authentication.application.port;

/** Cryptographically secure random values for recovery codes, temporary passwords, and refresh tokens. */
public interface SecureRandomGenerator {

  /** A numeric code of exactly {@code digits} digits (system_contract.md "Password Recovery Parameters (v1)": 6-digit). */
  String randomNumericCode(int digits);

  /** A URL-safe, base64-encoded random token of {@code byteLength} random bytes (refresh tokens: 256 bits = 32 bytes). */
  String randomUrlSafeToken(int byteLength);

  /** A temporary password meeting the v1 password policy length (system_contract.md: 12 chars, alphanumeric + symbols). */
  String randomTemporaryPassword();
}

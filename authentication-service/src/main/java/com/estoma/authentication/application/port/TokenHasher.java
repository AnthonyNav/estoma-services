package com.estoma.authentication.application.port;

/**
 * Fast, deterministic hashing for high-entropy, rate-limited secrets
 * (refresh tokens, recovery codes) -- deliberately not {@code
 * PasswordHasher}: Argon2's slow-by-design cost is wrong for a value that
 * must be looked up by hash on every request/validation, and these secrets
 * are already high-entropy or attempt-limited, unlike a user-chosen
 * password.
 */
public interface TokenHasher {

  String sha256Hex(String value);
}

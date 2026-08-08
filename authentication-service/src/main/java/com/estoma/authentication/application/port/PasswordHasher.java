package com.estoma.authentication.application.port;

import com.estoma.authentication.domain.credential.PasswordHash;

/**
 * Hashes and verifies passwords. Parameters are fixed in
 * system_contract.md "Security Parameters (v1)" (Argon2id); this port is
 * algorithm-agnostic so a future cost increase or algorithm change stays an
 * infrastructure-only concern.
 */
public interface PasswordHasher {

  PasswordHash hash(String plainTextPassword);

  boolean matches(String plainTextPassword, PasswordHash hash);
}

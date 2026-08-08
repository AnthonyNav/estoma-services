package com.estoma.authentication.infrastructure.security;

import com.estoma.authentication.application.port.PasswordHasher;
import com.estoma.authentication.domain.credential.PasswordHash;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing, parameters fixed in system_contract.md
 * "Security Parameters (v1)": 19 MiB memory, 2 iterations, parallelism 1,
 * 16-byte salt, 32-byte output. Uses {@code spring-security-crypto}'s
 * {@link Argon2PasswordEncoder}, already on the classpath transitively via
 * {@code spring-boot-starter-security} (also used for the HTTP lockdown) --
 * no separate dependency needed for hashing.
 *
 * <p>{@code algorithm}/{@code parametersJson} are stored alongside the
 * self-contained encoded hash so a future cost increase can be detected and
 * trigger a re-hash on next successful login, without parsing the encoded
 * value itself.
 */
@Component
public class Argon2PasswordHasher implements PasswordHasher {

  private static final String ALGORITHM = "ARGON2ID";
  private static final String PARAMETERS_JSON = "{\"memoryKb\":19456,\"iterations\":2,\"parallelism\":1,\"saltLength\":16,\"hashLength\":32}";

  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

  @Override
  public PasswordHash hash(String plainTextPassword) {
    return new PasswordHash(encoder.encode(plainTextPassword), ALGORITHM, PARAMETERS_JSON);
  }

  @Override
  public boolean matches(String plainTextPassword, PasswordHash hash) {
    return encoder.matches(plainTextPassword, hash.value());
  }
}

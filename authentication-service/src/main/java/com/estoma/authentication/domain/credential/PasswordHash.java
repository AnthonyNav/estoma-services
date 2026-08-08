package com.estoma.authentication.domain.credential;

/**
 * A hashed secret: never the plain-text password. {@code value} is the
 * self-contained encoded hash (algorithm + parameters + salt + digest, in
 * whatever format the hashing library produces); {@code algorithm} and
 * {@code parametersJson} are stored redundantly alongside it
 * (system_contract.md "Security Parameters (v1)") so a future cost
 * increase can detect and re-hash on next successful login without parsing
 * the encoded value itself.
 */
public record PasswordHash(String value, String algorithm, String parametersJson) {

  public PasswordHash {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("hash value must not be blank");
    }
    if (algorithm == null || algorithm.isBlank()) {
      throw new IllegalArgumentException("algorithm must not be blank");
    }
    if (parametersJson == null || parametersJson.isBlank()) {
      throw new IllegalArgumentException("parametersJson must not be blank");
    }
  }
}

package com.estoma.authentication.infrastructure.security;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Produces the RSA keypair {@link com.estoma.authentication.infrastructure.security.JwtTokenIssuer}
 * signs and verifies with (system_contract.md "Sessions and tokens": JWT
 * RS256).
 *
 * <p>Ephemeral keys are limited to local and test. A deployable environment
 * must provide both key parts and a stable {@code kid}; otherwise startup
 * fails instead of issuing tokens no other replica can validate.
 */
@Configuration
class JwtKeyConfig {

  @Bean
  SigningKeys jwtSigningKeys(
      @Value("${estoma.jwt.private-key:}") String privateKeyPem,
      @Value("${estoma.jwt.public-key:}") String publicKeyPem,
      @Value("${estoma.jwt.kid:}") String kid,
      @Value("${estoma.environment:local}") String environment) {
    if (!privateKeyPem.isBlank() && !publicKeyPem.isBlank()) {
      if (kid.isBlank()) {
        throw new IllegalStateException("estoma.jwt.kid must be configured when JWT keys are configured");
      }
      return new SigningKeys(loadKeyPair(privateKeyPem, publicKeyPem), kid);
    }
    if (!isLocalOrTest(environment)) {
      throw new IllegalStateException("JWT private/public keys and kid are required outside local and test environments");
    }
    return new SigningKeys(generateEphemeralKeyPair(), kid);
  }

  private static boolean isLocalOrTest(String environment) {
    return "local".equalsIgnoreCase(environment) || "test".equalsIgnoreCase(environment);
  }

  private static KeyPair generateEphemeralKeyPair() {
    try {
      java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key pair generation is unavailable", e);
    }
  }

  private static KeyPair loadKeyPair(String privateKeyPem, String publicKeyPem) {
    try {
      KeyFactory factory = KeyFactory.getInstance("RSA");
      PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyPem)));
      PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyPem)));
      return new KeyPair(publicKey, privateKey);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new IllegalStateException("configured JWT key pair is invalid", e);
    }
  }

  record SigningKeys(KeyPair keyPair, String kid) {}
}

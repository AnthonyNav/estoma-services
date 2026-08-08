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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Produces the RSA keypair {@link com.estoma.authentication.infrastructure.security.JwtTokenIssuer}
 * signs and verifies with (system_contract.md "Sessions and tokens": JWT
 * RS256).
 *
 * <p>If {@code estoma.jwt.private-key}/{@code estoma.jwt.public-key} (PEM,
 * PKCS8/X509, base64-encoded contents without the header/footer lines) are
 * both configured, they are used. Otherwise an ephemeral RSA-2048 keypair
 * is generated at startup -- sufficient for a single running instance
 * (local dev, tests, or a single-replica deployment), and intentionally not
 * more than that: real signing-key provisioning and rotation is a platform
 * secrets-management decision explicitly out of this service's control
 * (docs/pending_decisions.md). Tokens already carry a {@code kid} header,
 * so swapping to provisioned keys later needs no wire-format change.
 */
@Configuration
class JwtKeyConfig {

  @Bean
  SigningKeys jwtSigningKeys(
      @Value("${estoma.jwt.private-key:}") String privateKeyPem, @Value("${estoma.jwt.public-key:}") String publicKeyPem) {
    String kid = UUID.randomUUID().toString();
    if (!privateKeyPem.isBlank() && !publicKeyPem.isBlank()) {
      return new SigningKeys(loadKeyPair(privateKeyPem, publicKeyPem), kid);
    }
    return new SigningKeys(generateEphemeralKeyPair(), kid);
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

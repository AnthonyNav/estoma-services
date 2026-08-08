package com.estoma.authentication.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.TokenIssuer;
import com.estoma.authentication.infrastructure.security.JwtKeyConfig.SigningKeys;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtTokenIssuerTest {

  private final JwtTokenIssuer issuer = new JwtTokenIssuer(new SigningKeys(generateKeyPair(), "test-kid"));

  @Test
  void issuedTokenVerifiesBackToTheSameAccountId() {
    UUID accountId = UUID.randomUUID();

    TokenIssuer.IssuedAccessToken issued = issuer.issueAccessToken(accountId, UUID.randomUUID(), "MATRICULA");

    assertThat(issuer.verifyAndExtractAccountId(issued.token())).contains(accountId);
    assertThat(issued.expiresAt()).isAfter(Instant.now());
  }

  @Test
  void verifyRejectsAMalformedToken() {
    assertThat(issuer.verifyAndExtractAccountId("not-a-jwt")).isEmpty();
  }

  @Test
  void verifyRejectsATokenSignedByADifferentKey() {
    TokenIssuer.IssuedAccessToken issued = issuer.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), "USERNAME");
    JwtTokenIssuer otherIssuer = new JwtTokenIssuer(new SigningKeys(generateKeyPair(), "other-kid"));

    assertThat(otherIssuer.verifyAndExtractAccountId(issued.token())).isEmpty();
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

package com.estoma.authentication.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.TokenIssuer;
import com.estoma.authentication.infrastructure.security.JwtKeyConfig.SigningKeys;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtTokenIssuerTest {

  private final SigningKeys signingKeys = new SigningKeys(generateKeyPair(), "test-kid");
  private final JwtTokenIssuer issuer = new JwtTokenIssuer(signingKeys);

  @Test
  void issuedTokenVerifiesBackToTheSameAccountId() {
    UUID accountId = UUID.randomUUID();

    UUID personId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    TokenIssuer.IssuedAccessToken issued = issuer.issueAccessToken(accountId, personId, sessionId, "NORMAL");

    assertThat(issuer.verifyAndExtractAccountId(issued.token())).contains(accountId);
    assertThat(issued.expiresAt()).isAfter(Instant.now());
    var claims = Jwts.parser().verifyWith((java.security.interfaces.RSAPublicKey) signingKeys.keyPair().getPublic()).build().parseSignedClaims(issued.token()).getPayload();
    assertThat(claims).containsKeys("iss", "aud", "sub", "sid", "personId", "iat", "exp", "jti", "authState");
    assertThat(claims).doesNotContainKey("loginType");
    assertThat(claims.get("sid")).isEqualTo(sessionId.toString());
  }

  @Test
  void verifyRejectsAMalformedToken() {
    assertThat(issuer.verifyAndExtractAccountId("not-a-jwt")).isEmpty();
  }

  @Test
  void verifyRejectsATokenSignedByADifferentKey() {
    TokenIssuer.IssuedAccessToken issued = issuer.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "NORMAL");
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

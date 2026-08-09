package com.estoma.authentication.infrastructure.security;

import com.estoma.authentication.application.port.TokenIssuer;
import com.estoma.authentication.infrastructure.security.JwtKeyConfig.SigningKeys;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT RS256 access tokens (system_contract.md "Sessions and tokens"): 15-minute
 * lifetime, claims {@code sub} (accountId), {@code personId}, {@code
 * sid}, {@code authState}, {@code iat}, {@code exp}, {@code jti}; {@code kid} in the
 * header. No revocation in v1 -- the token is left to expire; revocation
 * acts on the session/refresh token, never the access token itself.
 */
@Component
public class JwtTokenIssuer implements TokenIssuer {

  private static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);

  private final SigningKeys signingKeys;
  private final String issuer;
  private final String audience;

  JwtTokenIssuer(SigningKeys signingKeys) {
    this(signingKeys, "estoma-authentication", "estoma-clients");
  }

  @Autowired
  JwtTokenIssuer(
      SigningKeys signingKeys,
      @Value("${estoma.jwt.issuer:}") String issuer,
      @Value("${estoma.jwt.audience:}") String audience) {
    this.signingKeys = signingKeys;
    if (issuer.isBlank() || audience.isBlank()) {
      throw new IllegalStateException("estoma.jwt.issuer and estoma.jwt.audience must be configured");
    }
    this.issuer = issuer;
    this.audience = audience;
  }

  @Override
  public IssuedAccessToken issueAccessToken(UUID accountId, UUID personId, UUID sessionId, String authState) {
    Instant now = Instant.now();
    Instant expiresAt = now.plus(ACCESS_TOKEN_LIFETIME);
    String token =
        Jwts.builder()
            .header()
            .keyId(signingKeys.kid())
            .and()
            .subject(accountId.toString())
            .issuer(issuer)
            .audience().add(audience).and()
            .claim("sid", sessionId.toString())
            .claim("personId", personId.toString())
            .claim("authState", authState)
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith((RSAPrivateKey) signingKeys.keyPair().getPrivate(), Jwts.SIG.RS256)
            .compact();
    return new IssuedAccessToken(token, expiresAt);
  }

  @Override
  public Optional<UUID> verifyAndExtractAccountId(String accessToken) {
    try {
      String subject =
          Jwts.parser()
               .verifyWith((RSAPublicKey) signingKeys.keyPair().getPublic())
               .requireIssuer(issuer)
               .requireAudience(audience)
              .build()
              .parseSignedClaims(accessToken)
              .getPayload()
              .getSubject();
      return Optional.of(UUID.fromString(subject));
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}

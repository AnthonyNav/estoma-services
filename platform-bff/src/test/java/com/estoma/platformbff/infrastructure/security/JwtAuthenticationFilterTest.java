package com.estoma.platformbff.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.platformbff.application.security.CurrentSecurityPrincipal.Principal;
import com.estoma.platformbff.application.security.SessionRevocationChecker;
import com.estoma.platformbff.infrastructure.config.PlatformProperties;
import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

class JwtAuthenticationFilterTest {
  private final KeyPair keyPair = generateKeyPair();
  private final UUID accountId = UUID.randomUUID();
  private final UUID personId = UUID.randomUUID();
  private final UUID sessionId = UUID.randomUUID();
  private final PlatformProperties properties = new PlatformProperties(
      "bff",
      new PlatformProperties.SecurityRpc(Duration.ofSeconds(10), Duration.ofSeconds(8), 0),
      new PlatformProperties.Jwt("issuer", "audience", jwkSet()),
      new PlatformProperties.Retention(7, 24, 7, 30, 30, 30));

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void validatesJwtLocallyAndCreatesRichPrincipal() throws Exception {
    var filter = filter((account, person, session, issuedAt) -> false);
    var request = protectedRequest(token("NORMAL", Instant.now().minusSeconds(2), Instant.now().plusSeconds(60), "audience"));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(200);
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication.getPrincipal()).isEqualTo(
        new Principal(accountId, personId, sessionId, "NORMAL"));
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .containsExactly("AUTH_STATE_NORMAL");
  }

  @Test
  void passwordChangeRequiredGetsOnlyRestrictedAuthority() throws Exception {
    var filter = filter((account, person, session, issuedAt) -> false);
    var request = protectedRequest(token(
        "PASSWORD_CHANGE_REQUIRED", Instant.now().minusSeconds(2), Instant.now().plusSeconds(60), "audience"));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting("authority")
        .containsExactly("AUTH_STATE_PASSWORD_CHANGE_REQUIRED");
  }

  @Test
  void publicRefreshIgnoresExpiredBearerBecauseRefreshTokenAuthenticatesTheCall() throws Exception {
    var filter = filter((account, person, session, issuedAt) -> false);
    var request = protectedRequest(token(
        "NORMAL", Instant.now().minusSeconds(120), Instant.now().minusSeconds(60), "audience"));
    request.setRequestURI("/api/v1/auth/refresh");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void rejectsRevokedSessionAndWrongAudienceOnProtectedRoutes() throws Exception {
    var revokedFilter = filter((account, person, session, issuedAt) -> true);
    var revokedResponse = new MockHttpServletResponse();
    revokedFilter.doFilter(
        protectedRequest(token("NORMAL", Instant.now().minusSeconds(2), Instant.now().plusSeconds(60), "audience")),
        revokedResponse,
        new MockFilterChain());
    assertThat(revokedResponse.getStatus()).isEqualTo(401);

    SecurityContextHolder.clearContext();
    var audienceFilter = filter((account, person, session, issuedAt) -> false);
    var audienceResponse = new MockHttpServletResponse();
    audienceFilter.doFilter(
        protectedRequest(token("NORMAL", Instant.now().minusSeconds(2), Instant.now().plusSeconds(60), "other-audience")),
        audienceResponse,
        new MockFilterChain());
    assertThat(audienceResponse.getStatus()).isEqualTo(401);
  }

  private JwtAuthenticationFilter filter(SessionRevocationChecker checker) {
    return new JwtAuthenticationFilter(
        new JwkKeyProvider(JsonMapper.builder().build(), properties), checker, properties);
  }

  private MockHttpServletRequest protectedRequest(String token) {
    var request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/me/password");
    request.addHeader("Authorization", "Bearer " + token);
    return request;
  }

  private String token(String authState, Instant issuedAt, Instant expiresAt, String audience) {
    return Jwts.builder()
        .header().keyId("test-kid").and()
        .subject(accountId.toString())
        .issuer("issuer")
        .audience().add(audience).and()
        .claim("sid", sessionId.toString())
        .claim("personId", personId.toString())
        .claim("authState", authState)
        .id(UUID.randomUUID().toString())
        .issuedAt(Date.from(issuedAt))
        .expiration(Date.from(expiresAt))
        .signWith((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256)
        .compact();
  }

  private String jwkSet() {
    var publicKey = (java.security.interfaces.RSAPublicKey) keyPair.getPublic();
    return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\","
        + "\"kid\":\"test-kid\",\"n\":\""
        + Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(publicKey.getModulus()))
        + "\",\"e\":\""
        + Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(publicKey.getPublicExponent()))
        + "\"}]}";
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    return bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
  }

  private static KeyPair generateKeyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}

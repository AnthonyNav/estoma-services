package com.estoma.platformbff.infrastructure.security;

import com.estoma.platformbff.application.security.CurrentSecurityPrincipal.Principal;
import com.estoma.platformbff.application.security.SessionRevocationChecker;
import com.estoma.platformbff.infrastructure.config.PlatformProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
  private static final long MAX_FUTURE_IAT_SKEW_SECONDS = 60L;
  private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
      "/api/v1/auth/login",
      "/api/v1/auth/refresh",
      "/api/v1/auth/password-recovery",
      "/api/v1/auth/password-recovery/validate",
      "/api/v1/auth/password-recovery/reset");

  private final JwkKeyProvider keys;
  private final SessionRevocationChecker revocations;
  private final PlatformProperties properties;

  public JwtAuthenticationFilter(
      JwkKeyProvider keys,
      SessionRevocationChecker revocations,
      PlatformProperties properties) {
    this.keys = keys;
    this.revocations = revocations;
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return PUBLIC_AUTH_PATHS.contains(path)
        || "/health".equals(path)
        || "/version".equals(path)
        || "/actuator/prometheus".equals(path)
        || path.equals("/actuator/health")
        || path.startsWith("/actuator/health/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header == null) {
      filterChain.doFilter(request, response);
      return;
    }
    if (!header.startsWith("Bearer ") || header.substring("Bearer ".length()).isBlank()) {
      reject(response);
      return;
    }

    String token = header.substring("Bearer ".length()).trim();
    try {
      RSAPublicKey key = keys.resolve(token);
      Jws<Claims> parsed = Jwts.parser()
          .verifyWith(key)
          .requireIssuer(properties.jwt().issuer())
          .requireAudience(properties.jwt().audience())
          .build()
          .parseSignedClaims(token);
      Claims claims = parsed.getPayload();

      UUID accountId = uuid(claims.getSubject());
      UUID sessionId = uuid(claims.get("sid", String.class));
      UUID personId = uuid(claims.get("personId", String.class));
      String authState = claims.get("authState", String.class);
      String jti = claims.getId();
      if (claims.getIssuedAt() == null || claims.getExpiration() == null
          || authState == null || jti == null || jti.isBlank()) {
        throw new IllegalArgumentException("required JWT claim missing");
      }
      long issuedAt = claims.getIssuedAt().toInstant().getEpochSecond();
      if (issuedAt > Instant.now().getEpochSecond() + MAX_FUTURE_IAT_SKEW_SECONDS) {
        throw new IllegalArgumentException("JWT iat is in the future");
      }
      if (revocations.isRevoked(accountId, personId, sessionId, issuedAt)) {
        reject(response);
        return;
      }

      String authority = switch (authState) {
        case "NORMAL" -> "AUTH_STATE_NORMAL";
        case "PASSWORD_CHANGE_REQUIRED" -> "AUTH_STATE_PASSWORD_CHANGE_REQUIRED";
        default -> throw new IllegalArgumentException("invalid auth state");
      };
      Principal principal = new Principal(accountId, personId, sessionId, authState);
      var authentication = UsernamePasswordAuthenticationToken.authenticated(
          principal, null, List.of(new SimpleGrantedAuthority(authority)));
      authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(authentication);
      filterChain.doFilter(request, response);
    } catch (RuntimeException invalidToken) {
      SecurityContextHolder.clearContext();
      log.debug("JWT rejected category=invalid-token");
      reject(response);
    }
  }

  private static UUID uuid(String value) {
    if (value == null) throw new IllegalArgumentException("UUID claim missing");
    return UUID.fromString(value);
  }

  private static void reject(HttpServletResponse response) throws IOException {
    SecurityProblemWriter.invalidToken(response);
  }
}

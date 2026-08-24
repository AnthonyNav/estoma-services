package com.estoma.platformbff.infrastructure.security;

import com.estoma.platformbff.infrastructure.config.PlatformProperties;
import io.jsonwebtoken.JwtException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class JwkKeyProvider {
  private final ObjectMapper mapper;
  private final Map<String, RSAPublicKey> keys;

  public JwkKeyProvider(ObjectMapper mapper, PlatformProperties properties) {
    this.mapper = mapper;
    this.keys = load(properties.jwt().jwkSet());
  }

  public RSAPublicKey resolve(String compactToken) {
    try {
      int separator = compactToken == null ? -1 : compactToken.indexOf('.');
      if (separator <= 0) throw new JwtException("invalid JWT header");
      String encodedHeader = compactToken.substring(0, separator);
      JsonNode header = mapper.readValue(
          new String(Base64.getUrlDecoder().decode(encodedHeader), StandardCharsets.UTF_8),
          JsonNode.class);
      if (!"RS256".equals(text(header, "alg"))) {
        throw new JwtException("unsupported JWT algorithm");
      }
      String kid = text(header, "kid");
      RSAPublicKey key = keys.get(kid);
      if (key == null) throw new JwtException("unknown JWT key id");
      return key;
    } catch (JwtException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new JwtException("invalid JWT header", invalid);
    }
  }

  private Map<String, RSAPublicKey> load(String jwkSet) {
    if (jwkSet == null || jwkSet.isBlank()) {
      return Map.of();
    }
    try {
      JsonNode root = mapper.readValue(jwkSet, JsonNode.class);
      if (!root.isObject() || !root.path("keys").isArray()) {
        throw new IllegalArgumentException("JWK Set must contain keys array");
      }
      Map<String, RSAPublicKey> loaded = new HashMap<>();
      for (JsonNode key : root.path("keys")) {
        if (!"RSA".equals(text(key, "kty"))) continue;
        if (key.hasNonNull("alg") && !"RS256".equals(text(key, "alg"))) continue;
        if (key.hasNonNull("use") && !"sig".equals(text(key, "use"))) continue;
        String kid = text(key, "kid");
        byte[] modulus = Base64.getUrlDecoder().decode(text(key, "n"));
        byte[] exponent = Base64.getUrlDecoder().decode(text(key, "e"));
        var spec = new RSAPublicKeySpec(new BigInteger(1, modulus), new BigInteger(1, exponent));
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        if (loaded.putIfAbsent(kid, publicKey) != null) {
          throw new IllegalArgumentException("duplicate JWK kid");
        }
      }
      if (loaded.isEmpty()) {
        throw new IllegalArgumentException("JWK Set contains no usable RSA signing keys");
      }
      return Map.copyOf(loaded);
    } catch (Exception invalid) {
      throw new IllegalStateException("BFF_JWK_SET is invalid", invalid);
    }
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field) || !node.get(field).isTextual()
        || node.get(field).asText().isBlank()) {
      throw new IllegalArgumentException("JWK/JWT field is missing: " + field);
    }
    return node.get(field).asText();
  }
}

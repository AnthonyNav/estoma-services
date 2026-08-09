package com.estoma.communications.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** SHA-256 over a fixed-order, null-normalized functional request representation. */
public final class RequestFingerprint {
  private RequestFingerprint() {}
  public static String sha256(String... values) {
    String canonical = String.join("\u001f", java.util.Arrays.stream(values).map(RequestFingerprint::canonicalValue).toList());
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
  private static String marker(String value) { return value == null ? "<null>" : value.isEmpty() ? "<empty>" : value; }

  private static String canonicalValue(String value) {
    if (value == null || value.isEmpty()) return marker(value);
    try {
      Object parsed = new ObjectMapper().readValue(value, Object.class);
      return new ObjectMapper().writeValueAsString(normalize(parsed));
    } catch (Exception ignored) {
      return marker(value);
    }
  }

  @SuppressWarnings("unchecked")
  private static Object normalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach((key, item) -> sorted.put(String.valueOf(key), normalize(item)));
      return sorted;
    }
    if (value instanceof List<?> list) return list.stream().map(RequestFingerprint::normalize).toList();
    return value;
  }
}

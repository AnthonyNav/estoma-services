package com.estoma.authorization.application.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Minimal deterministic JSON used by the transport-neutral application boundary. */
public final class CanonicalJson {

  private CanonicalJson() {}

  public static String write(Object value) {
    StringBuilder json = new StringBuilder();
    append(json, value);
    return json.toString();
  }

  public static String sha256(Object value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(write(value).getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        result.append(String.format("%02x", item));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  private static void append(StringBuilder json, Object value) {
    if (value == null) {
      json.append("null");
    } else if (value instanceof String || value instanceof Character || value instanceof UUID
        || value instanceof Instant || value instanceof Enum<?>) {
      appendString(json, value.toString());
    } else if (value instanceof Boolean || value instanceof Number) {
      json.append(value);
    } else if (value instanceof Map<?, ?> map) {
      json.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : new TreeMap<>(map).entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw new IllegalArgumentException("canonical JSON object keys must be strings");
        }
        if (!first) {
          json.append(',');
        }
        first = false;
        appendString(json, key);
        json.append(':');
        append(json, entry.getValue());
      }
      json.append('}');
    } else if (value instanceof Collection<?> collection) {
      json.append('[');
      boolean first = true;
      for (Object item : collection) {
        if (!first) {
          json.append(',');
        }
        first = false;
        append(json, item);
      }
      json.append(']');
    } else {
      throw new IllegalArgumentException("unsupported canonical JSON value: " + value.getClass());
    }
  }

  private static void appendString(StringBuilder json, String value) {
    json.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\b' -> json.append("\\b");
        case '\f' -> json.append("\\f");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> {
          if (character < 0x20) {
            json.append(String.format("\\u%04x", (int) character));
          } else {
            json.append(character);
          }
        }
      }
    }
    json.append('"');
  }
}
